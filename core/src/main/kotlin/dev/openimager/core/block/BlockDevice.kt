package dev.openimager.core.block

import java.io.Closeable

/**
 * A randomly addressable block device: a USB card reader, a raw `/dev/block` node on a rooted
 * phone, or a plain file when testing.
 *
 * All offsets and lengths are byte values but must be multiples of [blockSize]; callers that need
 * unaligned access go through [BlockDeviceIo].
 */
interface BlockDevice : Closeable {

    /** Logical sector size reported by the device, almost always 512. */
    val blockSize: Int

    /** Number of addressable blocks. */
    val blockCount: Long

    /** Human readable name, e.g. "SanDisk Ultra". */
    val name: String

    val sizeBytes: Long get() = blockSize * blockCount

    fun read(deviceOffset: Long, dst: ByteArray, dstOffset: Int = 0, length: Int = dst.size - dstOffset)

    fun write(deviceOffset: Long, src: ByteArray, srcOffset: Int = 0, length: Int = src.size - srcOffset)

    /** Pushes any device side write cache to stable storage. */
    fun flush()

    override fun close()
}

/** Thrown when a device rejects a command or disappears mid-transfer. */
class BlockDeviceException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

internal fun BlockDevice.requireAligned(offset: Long, length: Int) {
    if (offset % blockSize != 0L || length % blockSize != 0) {
        throw BlockDeviceException(
            "unaligned access: offset=$offset length=$length blockSize=$blockSize",
        )
    }
    if (offset < 0 || offset + length > sizeBytes) {
        throw BlockDeviceException("access out of range: offset=$offset length=$length size=$sizeBytes")
    }
}
