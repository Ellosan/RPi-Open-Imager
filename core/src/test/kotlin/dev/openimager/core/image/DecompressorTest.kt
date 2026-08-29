package dev.openimager.core.image

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random

/** Every archive shape the download servers serve has to come back out byte identical. */
class DecompressorTest {

    private val payload = ByteArray(512 * 1024).also { Random(42).nextBytes(it) }

    @Test
    fun `unwraps xz, gzip and bzip2 streams`() {
        assertArrayEquals(payload, roundTrip { XZCompressorOutputStream(it) })
        assertArrayEquals(payload, roundTrip { GzipCompressorOutputStream(it) })
        assertArrayEquals(payload, roundTrip { BZip2CompressorOutputStream(it) })
    }

    @Test
    fun `passes an uncompressed image through untouched`() {
        assertArrayEquals(payload, Decompressor.decompress(ByteArrayInputStream(payload)).readBytes())
    }

    @Test
    fun `takes the image out of a zip archive, skipping directory entries`() {
        val archive = ByteArrayOutputStream()
        ZipArchiveOutputStream(archive).use { zip ->
            zip.putArchiveEntry(ZipArchiveEntry("images/"))
            zip.closeArchiveEntry()
            zip.putArchiveEntry(ZipArchiveEntry("images/2026-01-01-raspios.img"))
            zip.write(payload)
            zip.closeArchiveEntry()
        }
        assertArrayEquals(payload, Decompressor.decompress(ByteArrayInputStream(archive.toByteArray())).readBytes())
    }

    @Test
    fun `explains that zstd images are not supported`() {
        val zstd = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0, 0, 0, 0)
        val error = assertThrows(IOException::class.java) {
            Decompressor.decompress(ByteArrayInputStream(zstd))
        }
        assertTrue(error.message!!, error.message!!.contains("zstd"))
    }

    private fun roundTrip(wrap: (ByteArrayOutputStream) -> java.io.OutputStream): ByteArray {
        val compressed = ByteArrayOutputStream()
        wrap(compressed).use { it.write(payload) }
        return Decompressor.decompress(ByteArrayInputStream(compressed.toByteArray())).readBytes()
    }
}
