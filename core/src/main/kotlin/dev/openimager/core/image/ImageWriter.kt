package dev.openimager.core.image

import dev.openimager.core.block.BlockDevice
import dev.openimager.core.customization.AppliedCustomization
import dev.openimager.core.customization.CustomizationApplier
import dev.openimager.core.customization.CustomizationSettings
import dev.openimager.core.customization.InitFormat
import dev.openimager.core.util.toHex
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

enum class WritePhase { PREPARING, WRITING, VERIFYING, CUSTOMISING, FINISHED }

data class WriteProgress(
    val phase: WritePhase,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val downloadedBytes: Long = 0,
) {
    /** 0..1, or null when the total is unknown and no bar can be drawn. */
    val fraction: Float? get() = if (totalBytes > 0) (bytesProcessed.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

data class WriteOptions(
    val verify: Boolean = true,
    val customization: CustomizationSettings? = null,
    /** Declared by the catalogue; null asks the writer to detect it from the boot partition. */
    val initFormat: InitFormat? = null,
)

data class WriteResult(
    val bytesWritten: Long,
    val sha256: String,
    val verified: Boolean,
    val customization: AppliedCustomization?,
)

class WriteCancelledException : IOException("the write was cancelled")

class ImageVerificationException(message: String) : IOException(message)

/**
 * Streams an image onto a block device, then verifies it and applies the first boot settings.
 *
 * The whole pipeline is single pass: bytes are decompressed, hashed and written in one go, so a
 * multi gigabyte image never needs to be staged on the phone's storage.
 */
class ImageWriter(
    private val source: ImageSource,
    private val target: BlockDevice,
    private val options: WriteOptions = WriteOptions(),
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val onProgress: (WriteProgress) -> Unit = {},
) {

    private val cancelled = AtomicBoolean(false)
    @Volatile private var downloadedBytes = 0L

    fun cancel() {
        cancelled.set(true)
    }

    fun write(): WriteResult {
        emit(WritePhase.PREPARING, 0, source.uncompressedSize, 0)
        if (source.uncompressedSize > 0 && source.uncompressedSize > target.sizeBytes) {
            throw IOException(
                "the image needs ${formatSize(source.uncompressedSize)} but " +
                    "${target.name} only has ${formatSize(target.sizeBytes)}",
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val bytesWritten = source.open { downloadedBytes = it }.use { raw ->
            Decompressor.decompress(raw).use { image -> copyToDevice(image, digest) }
        }
        target.flush()

        val sha256 = digest.digest().toHex()
        source.expectedSha256?.let { expected ->
            if (!expected.equals(sha256, ignoreCase = true)) {
                throw ImageVerificationException(
                    "the downloaded image does not match its published checksum - it may be corrupt",
                )
            }
        }

        val verified = if (options.verify) verify(bytesWritten, sha256) else false

        val customization = options.customization
            ?.takeIf { it.hasAnyImageChange }
            ?.let { settings ->
                emit(WritePhase.CUSTOMISING, bytesWritten, bytesWritten, 0)
                CustomizationApplier.apply(target, settings, options.initFormat)
            }

        emit(WritePhase.FINISHED, bytesWritten, bytesWritten, 0)
        return WriteResult(bytesWritten, sha256, verified, customization)
    }

    private fun copyToDevice(image: InputStream, digest: MessageDigest): Long {
        val blockSize = target.blockSize
        val buffer = ByteArray(bufferSize)
        var offset = 0L
        var filled = 0
        val started = System.nanoTime()
        var lastEmit = 0L

        while (true) {
            checkCancelled()
            val read = image.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
            if (filled == buffer.size) {
                writeChunk(buffer, filled, offset, digest, blockSize)
                offset += filled
                filled = 0
                lastEmit = maybeEmit(WritePhase.WRITING, offset, started, lastEmit)
            }
        }
        if (filled > 0) {
            writeChunk(buffer, filled, offset, digest, blockSize)
            offset += filled
        }
        if (offset == 0L) throw IOException("the image is empty")
        emit(WritePhase.WRITING, offset, totalBytes(offset), speed(offset, started))
        return offset
    }

    /** Writes [length] bytes, zero padding the tail so the device only ever sees whole blocks. */
    private fun writeChunk(buffer: ByteArray, length: Int, offset: Long, digest: MessageDigest, blockSize: Int) {
        digest.update(buffer, 0, length)
        val padded = ((length + blockSize - 1) / blockSize) * blockSize
        if (padded > length) java.util.Arrays.fill(buffer, length, padded, 0)
        if (offset + padded > target.sizeBytes) {
            throw IOException("the image is larger than ${target.name} (${formatSize(target.sizeBytes)})")
        }
        target.write(offset, buffer, 0, padded)
    }

    private fun verify(bytesWritten: Long, expected: String): Boolean {
        emit(WritePhase.VERIFYING, 0, bytesWritten, 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        var offset = 0L
        val started = System.nanoTime()
        var lastEmit = 0L

        while (offset < bytesWritten) {
            checkCancelled()
            val remaining = bytesWritten - offset
            val chunk = minOf(buffer.size.toLong(), ((remaining + target.blockSize - 1) / target.blockSize) * target.blockSize).toInt()
            target.read(offset, buffer, 0, chunk)
            digest.update(buffer, 0, minOf(chunk.toLong(), remaining).toInt())
            offset += minOf(chunk.toLong(), remaining)
            lastEmit = maybeEmit(WritePhase.VERIFYING, offset, started, lastEmit)
        }

        val actual = digest.digest().toHex()
        if (!actual.equals(expected, ignoreCase = true)) {
            throw ImageVerificationException(
                "verification failed: the card did not read back what was written to it",
            )
        }
        return true
    }

    private fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw WriteCancelledException()
    }

    private fun totalBytes(fallback: Long): Long =
        if (source.uncompressedSize > 0) source.uncompressedSize else fallback

    private fun speed(bytes: Long, startedNanos: Long): Long {
        val elapsed = (System.nanoTime() - startedNanos) / 1_000_000_000.0
        return if (elapsed > 0.05) (bytes / elapsed).toLong() else 0
    }

    private fun maybeEmit(phase: WritePhase, processed: Long, started: Long, lastEmit: Long): Long {
        val now = System.nanoTime()
        if (now - lastEmit < PROGRESS_INTERVAL_NANOS) return lastEmit
        emit(phase, processed, totalBytes(processed), speed(processed, started))
        return now
    }

    private fun emit(phase: WritePhase, processed: Long, total: Long, speed: Long) {
        onProgress(WriteProgress(phase, processed, total, speed, downloadedBytes))
    }

    companion object {
        /** Large enough to keep USB bulk transfers busy without stalling progress updates. */
        const val DEFAULT_BUFFER_SIZE = 1 shl 20
        private const val PROGRESS_INTERVAL_NANOS = 200_000_000L

        fun formatSize(bytes: Long): String {
            val units = listOf("B", "kB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1000 && unit < units.lastIndex) {
                value /= 1000
                unit++
            }
            return if (unit == 0) "$bytes B" else String.format("%.1f %s", value, units[unit])
        }
    }
}
