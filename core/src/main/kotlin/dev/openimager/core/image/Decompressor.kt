package dev.openimager.core.image

import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

enum class ImageFormat { RAW, XZ, GZIP, BZIP2, ZIP, ZSTD }

/** Picks the decompressor from the stream's magic bytes rather than trusting the file name. */
object Decompressor {

    private const val MAGIC_LENGTH = 8

    fun detect(stream: BufferedInputStream): ImageFormat {
        val magic = ByteArray(MAGIC_LENGTH)
        stream.mark(MAGIC_LENGTH)
        var read = 0
        while (read < MAGIC_LENGTH) {
            val n = stream.read(magic, read, MAGIC_LENGTH - read)
            if (n < 0) break
            read += n
        }
        stream.reset()
        return detect(magic, read)
    }

    fun detect(magic: ByteArray, length: Int = magic.size): ImageFormat {
        fun matches(vararg bytes: Int): Boolean =
            length >= bytes.size && bytes.withIndex().all { (i, b) -> magic[i].toInt() and 0xFF == b }

        return when {
            matches(0xFD, '7'.code, 'z'.code, 'X'.code, 'Z'.code, 0x00) -> ImageFormat.XZ
            matches(0x1F, 0x8B) -> ImageFormat.GZIP
            matches('B'.code, 'Z'.code, 'h'.code) -> ImageFormat.BZIP2
            matches('P'.code, 'K'.code, 0x03, 0x04) -> ImageFormat.ZIP
            matches(0x28, 0xB5, 0x2F, 0xFD) -> ImageFormat.ZSTD
            else -> ImageFormat.RAW
        }
    }

    /** Wraps [stream] so that reading it yields the raw disk image. */
    fun decompress(stream: InputStream): InputStream {
        val buffered = if (stream is BufferedInputStream) stream else BufferedInputStream(stream, 64 * 1024)
        return when (val format = detect(buffered)) {
            ImageFormat.RAW -> buffered
            ImageFormat.XZ -> XZCompressorInputStream(buffered, true)
            ImageFormat.GZIP -> GzipCompressorInputStream(buffered, true)
            ImageFormat.BZIP2 -> BZip2CompressorInputStream(buffered, true)
            ImageFormat.ZIP -> openZip(buffered)
            ImageFormat.ZSTD -> throw IOException(
                "zstd compressed images are not supported yet - download the .img.xz or .img.gz build instead",
            )
            else -> throw IOException("unsupported image format: $format")
        }
    }

    /** Single image archives are the norm, so the first regular entry is the image. */
    private fun openZip(stream: InputStream): InputStream {
        val zip = ZipArchiveInputStream(stream)
        while (true) {
            val entry = zip.nextEntry ?: throw IOException("the zip archive does not contain an image")
            if (entry.isDirectory) continue
            if (!zip.canReadEntryData(entry)) {
                throw IOException("the zip entry ${entry.name} uses an unsupported compression method")
            }
            return zip
        }
    }
}
