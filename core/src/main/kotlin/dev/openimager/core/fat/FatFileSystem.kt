package dev.openimager.core.fat

import dev.openimager.core.block.BlockDevice
import dev.openimager.core.util.setU16
import dev.openimager.core.util.setU32
import dev.openimager.core.util.u16
import dev.openimager.core.util.u32
import dev.openimager.core.util.u8
import java.io.Closeable
import java.io.IOException
import java.util.Calendar
import java.util.GregorianCalendar

enum class FatType { FAT12, FAT16, FAT32 }

class FatException(message: String) : IOException(message)

/**
 * A deliberately small FAT12/16/32 implementation: enough to read and rewrite files in the root
 * directory of a Raspberry Pi boot partition, which is all the OS customisation step needs.
 *
 * Files are always written as a fresh cluster chain, and directory entries get a real long file
 * name when the name does not fit 8.3 - the Pi firmware only ever looks at the short entry, so
 * names such as `cmdline.txt` keep their canonical `CMDLINE.TXT` short form.
 */
class FatFileSystem private constructor(
    private val device: BlockDevice,
    private val bytesPerSector: Int,
    private val sectorsPerCluster: Int,
    private val reservedSectors: Int,
    private val numberOfFats: Int,
    private val rootEntryCount: Int,
    private val fatSizeSectors: Long,
    private val totalSectors: Long,
    private val rootCluster: Long,
    private val fsInfoSector: Int,
    val type: FatType,
    val volumeLabel: String,
) : Closeable {

    private val bytesPerCluster = bytesPerSector * sectorsPerCluster
    private val rootDirSectors = ((rootEntryCount * 32) + bytesPerSector - 1) / bytesPerSector
    private val firstFatSector = reservedSectors.toLong()
    private val firstRootDirSector = reservedSectors + numberOfFats * fatSizeSectors
    private val firstDataSector = firstRootDirSector + rootDirSectors
    private val clusterCount = (totalSectors - firstDataSector) / sectorsPerCluster

    private val endOfChain: Long = when (type) {
        FatType.FAT12 -> 0xFFFL
        FatType.FAT16 -> 0xFFFFL
        FatType.FAT32 -> 0x0FFFFFFFL
    }

    private var cachedFatSector = -1L
    private var cachedFat: ByteArray? = null
    private var allocationHint = 2L

    // region public API

    /** Names of the files in the root directory, long name where one is present. */
    fun listRoot(): List<String> = parseDirectory(readRootDirectory()).map { it.name }

    fun exists(name: String): Boolean = findEntry(parseDirectory(readRootDirectory()), name) != null

    fun readFile(name: String): ByteArray? {
        val entry = findEntry(parseDirectory(readRootDirectory()), name) ?: return null
        if (entry.size == 0L || entry.firstCluster == 0L) return ByteArray(0)
        val chain = readChain(entry.firstCluster)
        val data = ByteArray(entry.size.toInt())
        var written = 0
        for (cluster in chain) {
            if (written >= data.size) break
            val buffer = readSectors(firstSectorOfCluster(cluster), sectorsPerCluster)
            val n = minOf(bytesPerCluster, data.size - written)
            System.arraycopy(buffer, 0, data, written, n)
            written += n
        }
        return data
    }

    /** Creates [name] in the root directory, replacing any existing file with that name. */
    fun writeFile(name: String, data: ByteArray, timestampMillis: Long = System.currentTimeMillis()) {
        require(name.isNotBlank()) { "empty file name" }
        var directory = readRootDirectory()
        var entries = parseDirectory(directory)

        findEntry(entries, name)?.let { existing ->
            if (existing.firstCluster != 0L) freeChain(existing.firstCluster)
            for (index in existing.firstSlot..existing.lastSlot) directory[index * 32] = 0xE5.toByte()
            entries = entries.filter { it !== existing }
        }

        val naming = ShortNameGenerator.generate(name, entries.map { it.rawShortName }.toSet())
        val slotsNeeded = if (naming.longNameEntries == 0) 1 else naming.longNameEntries + 1

        var slot = findFreeSlotRun(directory, slotsNeeded)
        if (slot < 0) {
            directory = growRootDirectory(directory)
            slot = findFreeSlotRun(directory, slotsNeeded)
            if (slot < 0) throw FatException("root directory is full")
        }

        val clustersNeeded = ((data.size.toLong() + bytesPerCluster - 1) / bytesPerCluster).toInt()
        val chain = if (clustersNeeded == 0) emptyList() else allocateChain(clustersNeeded)
        writeChainData(chain, data)

        writeDirectoryEntries(
            directory = directory,
            slot = slot,
            longName = name,
            naming = naming,
            firstCluster = chain.firstOrNull() ?: 0L,
            size = data.size.toLong(),
            timestampMillis = timestampMillis,
        )
        writeRootDirectory(directory)
        updateFsInfo()
        device.flush()
    }

    fun deleteFile(name: String): Boolean {
        val directory = readRootDirectory()
        val entry = findEntry(parseDirectory(directory), name) ?: return false
        if (entry.firstCluster != 0L) freeChain(entry.firstCluster)
        for (index in entry.firstSlot..entry.lastSlot) directory[index * 32] = 0xE5.toByte()
        writeRootDirectory(directory)
        updateFsInfo()
        device.flush()
        return true
    }

    override fun close() = Unit

    // endregion

    // region sector and cluster plumbing

    private fun readSectors(sector: Long, count: Int): ByteArray {
        val buffer = ByteArray(count * bytesPerSector)
        device.read(sector * bytesPerSector, buffer, 0, buffer.size)
        return buffer
    }

    private fun writeSectors(sector: Long, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        device.write(sector * bytesPerSector, data, offset, length)
    }

    private fun firstSectorOfCluster(cluster: Long): Long =
        firstDataSector + (cluster - 2) * sectorsPerCluster

    private fun readFatSector(sector: Long): ByteArray {
        cachedFat?.let { if (cachedFatSector == sector) return it }
        // FAT12 entries straddle sector boundaries, so keep a two sector window.
        val count = if (type == FatType.FAT12 && sector + 1 < firstFatSector + fatSizeSectors) 2 else 1
        val data = readSectors(sector, count)
        cachedFatSector = sector
        cachedFat = data
        return data
    }

    private fun fatEntryByteOffset(cluster: Long): Long = when (type) {
        FatType.FAT12 -> cluster + cluster / 2
        FatType.FAT16 -> cluster * 2
        FatType.FAT32 -> cluster * 4
    }

    private fun readFatEntry(cluster: Long): Long {
        val byteOffset = fatEntryByteOffset(cluster)
        val sector = firstFatSector + byteOffset / bytesPerSector
        val within = (byteOffset % bytesPerSector).toInt()
        val data = readFatSector(sector)
        return when (type) {
            FatType.FAT32 -> data.u32(within) and 0x0FFFFFFFL
            FatType.FAT16 -> data.u16(within).toLong()
            FatType.FAT12 -> {
                val raw = if (within + 1 < data.size) data.u16(within) else data.u8(within)
                if (cluster % 2 == 0L) (raw and 0x0FFF).toLong() else (raw ushr 4).toLong()
            }
        }
    }

    private fun writeFatEntry(cluster: Long, value: Long) {
        val byteOffset = fatEntryByteOffset(cluster)
        val sectorInFat = byteOffset / bytesPerSector
        val within = (byteOffset % bytesPerSector).toInt()
        val spansSectors = type == FatType.FAT12 && within == bytesPerSector - 1
        val sectorCount = if (spansSectors) 2 else 1

        for (copy in 0 until numberOfFats) {
            val sector = firstFatSector + copy * fatSizeSectors + sectorInFat
            val data = readSectors(sector, sectorCount)
            when (type) {
                FatType.FAT32 -> {
                    val preserved = data.u32(within) and 0xF0000000L
                    data.setU32(within, preserved or (value and 0x0FFFFFFFL))
                }
                FatType.FAT16 -> data.setU16(within, (value and 0xFFFF).toInt())
                FatType.FAT12 -> {
                    val current = if (within + 1 < data.size) data.u16(within) else data.u8(within)
                    val updated = if (cluster % 2 == 0L) {
                        (current and 0xF000) or (value.toInt() and 0x0FFF)
                    } else {
                        (current and 0x000F) or ((value.toInt() and 0x0FFF) shl 4)
                    }
                    data[within] = (updated and 0xFF).toByte()
                    if (within + 1 < data.size) data[within + 1] = ((updated ushr 8) and 0xFF).toByte()
                }
            }
            writeSectors(sector, data)
            if (copy == 0) {
                cachedFatSector = sector
                cachedFat = data
            }
        }
    }

    private fun isEndOfChain(value: Long): Boolean = when (type) {
        FatType.FAT12 -> value >= 0xFF8L
        FatType.FAT16 -> value >= 0xFFF8L
        FatType.FAT32 -> value >= 0x0FFFFFF8L
    }

    private fun readChain(start: Long): List<Long> {
        val chain = ArrayList<Long>()
        var cluster = start
        while (cluster >= 2 && cluster < clusterCount + 2) {
            chain += cluster
            val next = readFatEntry(cluster)
            if (isEndOfChain(next) || next < 2) break
            cluster = next
            if (chain.size > clusterCount) throw FatException("cluster chain loop detected")
        }
        return chain
    }

    private fun freeChain(start: Long) {
        for (cluster in readChain(start)) writeFatEntry(cluster, 0)
        allocationHint = 2
    }

    private fun allocateChain(count: Int, appendTo: Long = 0L): List<Long> {
        val allocated = ArrayList<Long>(count)
        var cluster = allocationHint
        var scanned = 0L
        while (allocated.size < count) {
            if (cluster >= clusterCount + 2) cluster = 2
            if (scanned++ > clusterCount) throw FatException("no free space on the boot partition")
            if (readFatEntry(cluster) == 0L) {
                // Reserve straight away so the next probe cannot hand out the same cluster.
                writeFatEntry(cluster, endOfChain)
                allocated += cluster
            }
            cluster++
        }
        allocationHint = cluster
        for (i in 0 until allocated.size - 1) writeFatEntry(allocated[i], allocated[i + 1])
        writeFatEntry(allocated.last(), endOfChain)
        if (appendTo != 0L) {
            val tail = readChain(appendTo).last()
            writeFatEntry(tail, allocated.first())
        }
        return allocated
    }

    private fun writeChainData(chain: List<Long>, data: ByteArray) {
        var position = 0
        val buffer = ByteArray(bytesPerCluster)
        for (cluster in chain) {
            val n = minOf(bytesPerCluster, data.size - position)
            java.util.Arrays.fill(buffer, 0)
            if (n > 0) System.arraycopy(data, position, buffer, 0, n)
            writeSectors(firstSectorOfCluster(cluster), buffer)
            position += n
        }
    }

    // endregion

    // region root directory

    private fun readRootDirectory(): ByteArray = when (type) {
        FatType.FAT32 -> {
            val chain = readChain(rootCluster)
            val out = ByteArray(chain.size * bytesPerCluster)
            chain.forEachIndexed { index, cluster ->
                val data = readSectors(firstSectorOfCluster(cluster), sectorsPerCluster)
                System.arraycopy(data, 0, out, index * bytesPerCluster, bytesPerCluster)
            }
            out
        }
        else -> readSectors(firstRootDirSector, rootDirSectors)
    }

    private fun writeRootDirectory(data: ByteArray) {
        when (type) {
            FatType.FAT32 -> {
                val chain = readChain(rootCluster)
                chain.forEachIndexed { index, cluster ->
                    val offset = index * bytesPerCluster
                    if (offset < data.size) {
                        writeSectors(firstSectorOfCluster(cluster), data, offset, bytesPerCluster)
                    }
                }
            }
            else -> writeSectors(firstRootDirSector, data)
        }
    }

    private fun growRootDirectory(current: ByteArray): ByteArray {
        if (type != FatType.FAT32) throw FatException("the root directory of this FAT volume is full")
        val added = allocateChain(1, appendTo = rootCluster)
        writeChainData(added, ByteArray(0))
        return current.copyOf(current.size + bytesPerCluster)
    }

    private fun findFreeSlotRun(directory: ByteArray, slots: Int): Int {
        var run = 0
        var index = 0
        val total = directory.size / 32
        while (index < total) {
            val marker = directory.u8(index * 32)
            if (marker == 0x00 || marker == 0xE5) {
                run++
                if (run == slots) return index - slots + 1
            } else {
                run = 0
            }
            index++
        }
        return -1
    }

    private data class DirectoryEntry(
        val name: String,
        val rawShortName: String,
        val firstCluster: Long,
        val size: Long,
        val attributes: Int,
        val firstSlot: Int,
        val lastSlot: Int,
    )

    private fun parseDirectory(directory: ByteArray): List<DirectoryEntry> {
        val entries = ArrayList<DirectoryEntry>()
        val longNameParts = sortedMapOf<Int, String>()
        var longNameStart = -1
        var index = 0
        val total = directory.size / 32
        while (index < total) {
            val base = index * 32
            when (directory.u8(base)) {
                0x00 -> return entries
                0xE5 -> {
                    longNameParts.clear()
                    longNameStart = -1
                }
                else -> {
                    val attributes = directory.u8(base + 11)
                    if (attributes and 0x3F == 0x0F) {
                        if (longNameStart < 0) longNameStart = index
                        longNameParts[directory.u8(base) and 0x3F] = decodeLongNameChunk(directory, base)
                    } else if (attributes and 0x08 != 0 && attributes and 0x10 == 0) {
                        longNameParts.clear()
                        longNameStart = -1 // volume label
                    } else {
                        val longName = if (longNameParts.isEmpty()) null else longNameParts.values.joinToString("")
                        entries += DirectoryEntry(
                            name = longName?.takeIf { it.isNotEmpty() } ?: decodeShortName(directory, base),
                            rawShortName = rawShortName(directory, base),
                            firstCluster = (directory.u16(base + 20).toLong() shl 16) or
                                directory.u16(base + 26).toLong(),
                            size = directory.u32(base + 28),
                            attributes = attributes,
                            firstSlot = if (longNameStart >= 0) longNameStart else index,
                            lastSlot = index,
                        )
                        longNameParts.clear()
                        longNameStart = -1
                    }
                }
            }
            index++
        }
        return entries
    }

    private fun findEntry(entries: List<DirectoryEntry>, name: String): DirectoryEntry? =
        entries.firstOrNull { it.name.equals(name, ignoreCase = true) && it.attributes and 0x10 == 0 }

    private fun decodeLongNameChunk(directory: ByteArray, base: Int): String {
        val chars = StringBuilder(13)
        for (offset in LFN_CHAR_OFFSETS) {
            val code = directory.u16(base + offset)
            if (code == 0x0000 || code == 0xFFFF) break
            chars.append(code.toChar())
        }
        return chars.toString()
    }

    private fun rawShortName(directory: ByteArray, base: Int): String =
        String(directory, base, 11, Charsets.ISO_8859_1)

    private fun decodeShortName(directory: ByteArray, base: Int): String {
        val raw = rawShortName(directory, base)
        val flags = directory.u8(base + 12)
        // 0x05 in the first byte stands in for a leading 0xE5, which marks a deleted entry.
        var stem = raw.substring(0, 8).trimEnd()
        if (stem.startsWith('\u0005')) stem = "\u00E5" + stem.substring(1)
        var extension = raw.substring(8, 11).trimEnd()
        if (flags and 0x08 != 0) stem = stem.lowercase()
        if (flags and 0x10 != 0) extension = extension.lowercase()
        return if (extension.isEmpty()) stem else "$stem.$extension"
    }

    private fun writeDirectoryEntries(
        directory: ByteArray,
        slot: Int,
        longName: String,
        naming: ShortNameGenerator.Result,
        firstCluster: Long,
        size: Long,
        timestampMillis: Long,
    ) {
        val shortNameBytes = naming.shortName.toByteArray(Charsets.ISO_8859_1)
        val checksum = shortNameChecksum(shortNameBytes)

        for (i in 0 until naming.longNameEntries) {
            val sequence = naming.longNameEntries - i
            val base = (slot + i) * 32
            java.util.Arrays.fill(directory, base, base + 32, 0)
            directory[base] = (sequence or if (i == 0) 0x40 else 0x00).toByte()
            directory[base + 11] = 0x0F
            directory[base + 13] = checksum
            for (c in 0 until 13) {
                val charIndex = (sequence - 1) * 13 + c
                val value = when {
                    charIndex < longName.length -> longName[charIndex].code
                    charIndex == longName.length -> 0x0000
                    else -> 0xFFFF
                }
                directory.setU16(base + LFN_CHAR_OFFSETS[c], value)
            }
        }

        val base = (slot + naming.longNameEntries) * 32
        java.util.Arrays.fill(directory, base, base + 32, 0)
        System.arraycopy(shortNameBytes, 0, directory, base, 11)
        directory[base + 11] = 0x20 // ARCHIVE
        directory[base + 12] = naming.caseFlags.toByte()
        val (date, time) = fatDateTime(timestampMillis)
        directory.setU16(base + 14, time)
        directory.setU16(base + 16, date)
        directory.setU16(base + 18, date)
        directory.setU16(base + 20, ((firstCluster ushr 16) and 0xFFFF).toInt())
        directory.setU16(base + 22, time)
        directory.setU16(base + 24, date)
        directory.setU16(base + 26, (firstCluster and 0xFFFF).toInt())
        directory.setU32(base + 28, size)
    }

    private fun updateFsInfo() {
        if (type != FatType.FAT32 || fsInfoSector <= 0 || fsInfoSector >= reservedSectors) return
        val sector = readSectors(fsInfoSector.toLong(), 1)
        if (sector.u32(0) != 0x41615252L || sector.u32(484) != 0x61417272L) return
        // Mark the cached free cluster count unknown rather than leaving a stale value behind.
        sector.setU32(488, 0xFFFFFFFFL)
        sector.setU32(492, 0xFFFFFFFFL)
        writeSectors(fsInfoSector.toLong(), sector)
    }

    // endregion

    companion object {

        private val LFN_CHAR_OFFSETS = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)

        fun read(device: BlockDevice): FatFileSystem {
            val boot = ByteArray(maxOf(512, device.blockSize))
            device.read(0, boot, 0, boot.size)

            val bytesPerSector = boot.u16(11)
            val sectorsPerCluster = boot.u8(13)
            val reservedSectors = boot.u16(14)
            val numberOfFats = boot.u8(16)
            val rootEntryCount = boot.u16(17)
            val fatSize16 = boot.u16(22).toLong()
            val fatSize32 = boot.u32(36)
            val totalSectors16 = boot.u16(19).toLong()
            val totalSectors32 = boot.u32(32)

            if (bytesPerSector !in intArrayOf(512, 1024, 2048, 4096)) {
                throw FatException("not a FAT filesystem (bytes per sector = $bytesPerSector)")
            }
            if (sectorsPerCluster == 0 || sectorsPerCluster and (sectorsPerCluster - 1) != 0) {
                throw FatException("invalid cluster size ($sectorsPerCluster sectors)")
            }
            if (numberOfFats !in 1..4 || reservedSectors == 0) throw FatException("invalid FAT header")

            val fatSize = if (fatSize16 != 0L) fatSize16 else fatSize32
            val totalSectors = if (totalSectors16 != 0L) totalSectors16 else totalSectors32
            if (fatSize == 0L || totalSectors == 0L) throw FatException("invalid FAT header")

            val rootDirSectors = ((rootEntryCount * 32) + bytesPerSector - 1) / bytesPerSector
            val dataSectors = totalSectors - (reservedSectors + numberOfFats * fatSize + rootDirSectors)
            val clusters = dataSectors / sectorsPerCluster
            val type = when {
                clusters < 4085 -> FatType.FAT12
                clusters < 65525 -> FatType.FAT16
                else -> FatType.FAT32
            }

            val label = if (type == FatType.FAT32) {
                String(boot, 71, 11, Charsets.ISO_8859_1).trim()
            } else {
                String(boot, 43, 11, Charsets.ISO_8859_1).trim()
            }

            return FatFileSystem(
                device = device,
                bytesPerSector = bytesPerSector,
                sectorsPerCluster = sectorsPerCluster,
                reservedSectors = reservedSectors,
                numberOfFats = numberOfFats,
                rootEntryCount = rootEntryCount,
                fatSizeSectors = fatSize,
                totalSectors = totalSectors,
                rootCluster = if (type == FatType.FAT32) boot.u32(44) else 0L,
                fsInfoSector = if (type == FatType.FAT32) boot.u16(48) else 0,
                type = type,
                volumeLabel = label,
            )
        }

        internal fun shortNameChecksum(name: ByteArray): Byte {
            var sum = 0
            for (i in 0 until 11) {
                sum = (((sum and 1) shl 7) + (sum ushr 1) + (name[i].toInt() and 0xFF)) and 0xFF
            }
            return sum.toByte()
        }

        internal fun fatDateTime(millis: Long): Pair<Int, Int> {
            val calendar = GregorianCalendar()
            calendar.timeInMillis = millis
            val year = (calendar.get(Calendar.YEAR) - 1980).coerceIn(0, 127)
            val date = (year shl 9) or ((calendar.get(Calendar.MONTH) + 1) shl 5) or
                calendar.get(Calendar.DAY_OF_MONTH)
            val time = (calendar.get(Calendar.HOUR_OF_DAY) shl 11) or
                (calendar.get(Calendar.MINUTE) shl 5) or (calendar.get(Calendar.SECOND) / 2)
            return date to time
        }
    }
}
