package dev.openimager.core.fat

import dev.openimager.core.block.FileBlockDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Exercises the FAT writer against volumes made by mkfs.vfat and checks the result with
 * fsck.vfat and mtools, so a corrupt directory or FAT chain fails the build rather than a card.
 */
class FatFileSystemTest {

    @Test
    fun `writes files to a FAT32 volume`() = withVolume(sizeMib = 300, fatBits = 32) { image ->
        val written = LinkedHashMap<String, ByteArray>()
        FileBlockDevice(image).use { device ->
            val fs = FatFileSystem.read(device)
            assertEquals(FatType.FAT32, fs.type)
            written["config.txt"] = "dtparam=audio=on\n".toByteArray()
            written["cmdline.txt"] = "console=serial0,115200 root=PARTUUID=deadbeef-02 rootwait\n".toByteArray()
            written["firstrun.sh"] = "#!/bin/bash\nset -e\n".toByteArray()
            written["ssh"] = ByteArray(0)
            written["custom.toml"] = "[system]\nhostname = \"raspberrypi\"\n".toByteArray()
            written["user-data"] = "#cloud-config\nhostname: pi\n".toByteArray()
            written["network-config"] = "version: 2\n".toByteArray()
            written["big.bin"] = randomBytes(3 * 1024 * 1024)
            written.forEach { (name, data) -> fs.writeFile(name, data) }
        }

        fsckClean(image)
        val listing = mdir(image)
        written.keys.forEach { name -> assertTrue("$name missing from $listing", listing.contains(name)) }

        FileBlockDevice(image).use { device ->
            val fs = FatFileSystem.read(device)
            written.forEach { (name, data) -> assertArrayEquals(name, data, fs.readFile(name)) }
            assertArrayEquals(written["big.bin"], mtype(image, "big.bin"))
        }
    }

    @Test
    fun `keeps canonical short names so the bootloader still finds cmdline txt`() =
        withVolume(sizeMib = 64, fatBits = 32) { image ->
            FileBlockDevice(image).use { device ->
                FatFileSystem.read(device).writeFile("cmdline.txt", "rootwait\n".toByteArray())
            }
            fsckClean(image)
            // mdir prints the 8.3 entry; a CMDLIN~1.TXT here would leave the card unbootable.
            val listing = run("mdir", "-i", image.path, "::").output
            assertTrue(listing, listing.uppercase().contains("CMDLINE  TXT"))
            assertTrue(listing, !listing.contains("~"))
        }

    @Test
    fun `replaces an existing file and frees its clusters`() =
        withVolume(sizeMib = 64, fatBits = 32) { image ->
            val large = randomBytes(1024 * 1024)
            val small = "replaced\n".toByteArray()
            FileBlockDevice(image).use { device ->
                val fs = FatFileSystem.read(device)
                fs.writeFile("custom.toml", large)
                fs.writeFile("custom.toml", small)
                assertArrayEquals(small, fs.readFile("custom.toml"))
                assertEquals(1, fs.listRoot().count { it == "custom.toml" })
            }
            fsckClean(image)
            assertArrayEquals(small, mtype(image, "custom.toml"))
        }

    @Test
    fun `writes files to a FAT16 volume`() = withVolume(sizeMib = 48, fatBits = 16) { image ->
        val payload = randomBytes(256 * 1024)
        FileBlockDevice(image).use { device ->
            val fs = FatFileSystem.read(device)
            assertEquals(FatType.FAT16, fs.type)
            fs.writeFile("custom.toml", payload)
            fs.writeFile("ssh", ByteArray(0))
        }
        fsckClean(image)
        assertArrayEquals(payload, mtype(image, "custom.toml"))
    }

    @Test
    fun `writes files to a FAT12 volume`() = withVolume(sizeMib = 8, fatBits = 12) { image ->
        val payload = randomBytes(64 * 1024)
        FileBlockDevice(image).use { device ->
            val fs = FatFileSystem.read(device)
            assertEquals(FatType.FAT12, fs.type)
            fs.writeFile("firstrun.sh", payload)
        }
        fsckClean(image)
        assertArrayEquals(payload, mtype(image, "firstrun.sh"))
    }

    @Test
    fun `grows the FAT32 root directory when it fills up`() =
        withVolume(sizeMib = 64, fatBits = 32) { image ->
            FileBlockDevice(image).use { device ->
                val fs = FatFileSystem.read(device)
                // Long names burn several slots each, so this overflows the initial root cluster.
                repeat(120) { index -> fs.writeFile("overlay-file-number-$index.dtbo", "x$index".toByteArray()) }
                assertEquals("x119", String(fs.readFile("overlay-file-number-119.dtbo")!!))
            }
            fsckClean(image)
            val listing = mdir(image)
            assertTrue(listing, listing.contains("overlay-file-number-119.dtbo"))
        }

    // region harness

    private fun withVolume(sizeMib: Int, fatBits: Int, body: (File) -> Unit) {
        assumeTrue("mkfs.vfat, fsck.vfat and mtools are required", toolsAvailable)
        val image = File.createTempFile("fat$fatBits-", ".img")
        try {
            java.io.RandomAccessFile(image, "rw").use { it.setLength(sizeMib.toLong() * 1024 * 1024) }
            run(MKFS!!, "-F", fatBits.toString(), "-n", "bootfs", image.path)
            body(image)
        } finally {
            image.delete()
        }
    }

    private fun fsckClean(image: File) {
        val result = run(FSCK!!, "-n", "-v", image.path, allowFailure = true)
        assertEquals("fsck.vfat reported problems:\n${result.output}", 0, result.exitCode)
    }

    private fun mdir(image: File): String = run("mdir", "-i", image.path, "-b", "::").output

    private fun mtype(image: File, name: String): ByteArray {
        val process = ProcessBuilder("mtype", "-i", image.path, "::$name").start()
        val bytes = process.inputStream.readBytes()
        assertEquals("mtype $name failed", 0, process.waitFor())
        return bytes
    }

    private data class Result(val exitCode: Int, val output: String)

    private fun run(vararg command: String, allowFailure: Boolean = false): Result {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .also { it.environment()["MTOOLS_SKIP_CHECK"] = "1" }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0 && !allowFailure) {
            throw AssertionError("${command.joinToString(" ")} failed ($exitCode):\n$output")
        }
        return Result(exitCode, output)
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }

    private companion object {
        val MKFS = listOf("/usr/sbin/mkfs.vfat", "/sbin/mkfs.vfat", "mkfs.vfat").firstOrNull(::exists)
        val FSCK = listOf("/usr/sbin/fsck.vfat", "/sbin/fsck.vfat", "fsck.vfat").firstOrNull(::exists)
        val toolsAvailable = MKFS != null && FSCK != null && exists("mdir") && exists("mtype")

        fun exists(command: String): Boolean = try {
            if (command.startsWith("/")) File(command).canExecute()
            else ProcessBuilder("which", command).start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    // endregion
}
