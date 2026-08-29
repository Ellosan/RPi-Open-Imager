package dev.openimager.core.block

import dev.openimager.core.util.u32
import dev.openimager.core.util.u8

/** One entry of an MBR partition table. */
data class Partition(
    val index: Int,
    val type: Int,
    val firstBlock: Long,
    val blockCount: Long,
) {
    val isFat: Boolean get() = type in FAT_TYPES

    val sizeBytes: Long get() = blockCount * 512

    companion object {
        private val FAT_TYPES = setOf(0x01, 0x04, 0x06, 0x0B, 0x0C, 0x0E)
    }
}

/**
 * Reads the classic MBR partition table written by every Raspberry Pi OS image. GPT images are not
 * used by the Pi bootloader chain, so an unrecognised table simply yields an empty list.
 */
object PartitionTable {

    fun read(device: BlockDevice): List<Partition> {
        val sector = ByteArray(maxOf(512, device.blockSize))
        device.read(0, sector, 0, device.blockSize)
        if (sector.u8(510) != 0x55 || sector.u8(511) != 0xAA) return emptyList()

        val partitions = ArrayList<Partition>(4)
        for (i in 0 until 4) {
            val base = 446 + i * 16
            val type = sector.u8(base + 4)
            if (type == 0) continue
            val firstBlock = sector.u32(base + 8)
            val blockCount = sector.u32(base + 12)
            if (blockCount == 0L) continue
            partitions += Partition(i, type, firstBlock, blockCount)
        }
        return partitions
    }

    /** The boot partition of a Pi image: the first FAT partition on the card. */
    fun findBootPartition(device: BlockDevice): Partition? =
        read(device).firstOrNull { it.isFat }
}
