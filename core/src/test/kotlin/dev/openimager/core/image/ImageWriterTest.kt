package dev.openimager.core.image

import dev.openimager.core.block.FileBlockDevice
import dev.openimager.core.block.PartitionTable
import dev.openimager.core.customization.CustomizationSettings
import dev.openimager.core.customization.InitFormat
import dev.openimager.core.util.toHex
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Random

/** End to end: build a Pi shaped image, stream it onto a card and customise the boot partition. */
class ImageWriterTest {

    private val partitionOffset = 4L * 1024 * 1024
    private val bootPartitionSize = 48L * 1024 * 1024

    @Test
    fun `writes a compressed image, verifies it and customises the boot partition`() {
        assumeTrue("mkfs.vfat and mtools are required", toolsAvailable())
        val image = buildDiskImage()
        val compressed = xzCompress(image)
        val card = blankCard(sizeBytes = 128L * 1024 * 1024)

        try {
            val expectedSha = sha256(image)
            val progress = ArrayList<WriteProgress>()
            val settings = CustomizationSettings(
                enabled = true,
                hostname = "openpi",
                username = "maker",
                passwordCrypt = "\$6\$salt\$hash",
                enableSsh = true,
            )

            val result = FileBlockDevice(card).use { device ->
                ImageWriter(
                    source = SimpleImageSource(
                        displayName = "test.img.xz",
                        compressedSize = compressed.length(),
                        uncompressedSize = image.length(),
                        expectedSha256 = expectedSha,
                        opener = { FileInputStream(compressed) },
                    ),
                    target = device,
                    options = WriteOptions(verify = true, customization = settings, initFormat = InitFormat.SYSTEMD),
                    onProgress = { progress += it },
                ).write()
            }

            assertEquals(image.length(), result.bytesWritten)
            assertEquals(expectedSha, result.sha256)
            assertTrue("the read back check should have run", result.verified)
            assertTrue(progress.any { it.phase == WritePhase.WRITING })
            assertTrue(progress.any { it.phase == WritePhase.VERIFYING })
            assertEquals(WritePhase.FINISHED, progress.last().phase)

            val applied = assertNotNull(result.customization).let { result.customization!! }
            assertEquals(InitFormat.SYSTEMD, applied.initFormat)
            assertTrue(applied.filesWritten.contains("firstrun.sh"))

            val listing = mtools("mdir", "-i", "${card.path}@@$partitionOffset", "-b", "::")
            assertTrue(listing, listing.contains("firstrun.sh"))
            val cmdline = mtools("mtype", "-i", "${card.path}@@$partitionOffset", "::cmdline.txt")
            assertTrue(cmdline, cmdline.contains("systemd.run=/boot/firstrun.sh"))
            assertTrue("the original kernel arguments must survive", cmdline.contains("rootwait"))

            FileBlockDevice(card).use { device ->
                val boot = PartitionTable.findBootPartition(device)
                assertNotNull(boot)
                assertEquals(partitionOffset / 512, boot!!.firstBlock)
            }
        } finally {
            listOf(image, compressed, card).forEach { it.delete() }
        }
    }

    @Test
    fun `refuses to start when the card is smaller than the image`() {
        val card = blankCard(sizeBytes = 8L * 1024 * 1024)
        try {
            val error = assertThrows(IOException::class.java) {
                FileBlockDevice(card).use { device ->
                    ImageWriter(
                        source = SimpleImageSource(
                            displayName = "huge.img",
                            uncompressedSize = 64L * 1024 * 1024,
                            opener = { ByteArrayInputStream(ByteArray(1024)) },
                        ),
                        target = device,
                    ).write()
                }
            }
            assertTrue(error.message!!, error.message!!.contains("only has"))
        } finally {
            card.delete()
        }
    }

    @Test
    fun `stops when cancelled`() {
        val card = blankCard(sizeBytes = 32L * 1024 * 1024)
        try {
            FileBlockDevice(card).use { device ->
                lateinit var writer: ImageWriter
                writer = ImageWriter(
                    source = SimpleImageSource(
                        displayName = "slow.img",
                        opener = { ByteArrayInputStream(ByteArray(16 * 1024 * 1024)) },
                    ),
                    target = device,
                    bufferSize = 64 * 1024,
                    onProgress = { writer.cancel() },
                )
                assertThrows(WriteCancelledException::class.java) { writer.write() }
            }
        } finally {
            card.delete()
        }
    }

    @Test
    fun `detects the compression format from the magic bytes`() {
        assertEquals(ImageFormat.XZ, Decompressor.detect(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)))
        assertEquals(ImageFormat.GZIP, Decompressor.detect(byteArrayOf(0x1F, 0x8B.toByte(), 0x08)))
        assertEquals(ImageFormat.ZIP, Decompressor.detect(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertEquals(ImageFormat.ZSTD, Decompressor.detect(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())))
        assertEquals(ImageFormat.RAW, Decompressor.detect(ByteArray(8)))
    }

    // region fixtures

    /** An MBR with a single FAT32 partition, laid out the way a Raspberry Pi OS image is. */
    private fun buildDiskImage(): File {
        val boot = File.createTempFile("bootfs-", ".img")
        RandomAccessFile(boot, "rw").use { it.setLength(bootPartitionSize) }
        run("mkfs.vfat", "-F", "32", "-n", "bootfs", boot.path)
        run("mcopy", "-i", boot.path, "-o", writeTemp("cmdline.txt", "console=serial0,115200 rootwait\n").path, "::cmdline.txt")
        run("mcopy", "-i", boot.path, "-o", writeTemp("issue.txt", "Raspberry Pi reference, generated using pi-gen\n").path, "::issue.txt")

        val disk = File.createTempFile("disk-", ".img")
        RandomAccessFile(disk, "rw").use { file ->
            file.setLength(partitionOffset + bootPartitionSize)
            val mbr = ByteArray(512)
            Random(7).nextBytes(ByteArray(0)) // keep the boot code area zeroed
            val entry = 446
            mbr[entry] = 0x80.toByte() // bootable
            mbr[entry + 4] = 0x0C // FAT32 LBA
            putLe32(mbr, entry + 8, partitionOffset / 512)
            putLe32(mbr, entry + 12, bootPartitionSize / 512)
            mbr[510] = 0x55
            mbr[511] = 0xAA.toByte()
            file.seek(0)
            file.write(mbr)
            file.seek(partitionOffset)
            FileInputStream(boot).use { it.copyTo(java.io.BufferedOutputStream(java.io.FileOutputStream(file.fd))) }
        }
        boot.delete()
        return disk
    }

    private fun writeTemp(name: String, content: String): File =
        File.createTempFile(name, null).also { it.writeText(content) }

    private fun xzCompress(source: File): File {
        val compressed = File.createTempFile("image-", ".img.xz")
        XZCompressorOutputStream(compressed.outputStream().buffered()).use { out ->
            FileInputStream(source).use { it.copyTo(out) }
        }
        return compressed
    }

    private fun blankCard(sizeBytes: Long): File =
        File.createTempFile("card-", ".img").also { card ->
            RandomAccessFile(card, "rw").use { it.setLength(sizeBytes) }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun putLe32(target: ByteArray, index: Int, value: Long) {
        for (i in 0 until 4) target[index + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true)
            .also { it.environment()["MTOOLS_SKIP_CHECK"] = "1" }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw AssertionError("${command.joinToString(" ")} failed:\n$output")
        return output
    }

    private fun mtools(vararg command: String): String = run(*command)

    private fun toolsAvailable(): Boolean = try {
        ProcessBuilder("which", "mkfs.vfat", "mcopy", "mdir", "mtype").start().waitFor() == 0
    } catch (e: Exception) {
        false
    }

    // endregion
}
