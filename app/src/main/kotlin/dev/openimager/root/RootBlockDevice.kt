package dev.openimager.root

import dev.openimager.core.block.BlockDevice
import dev.openimager.core.block.BlockDeviceException
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes a raw block device through `su`, using a `dd` process per direction.
 *
 * Spawning a process per megabyte would be far too slow, so each direction keeps its process alive
 * while access stays sequential - which is exactly what streaming an image does - and only restarts
 * it when the caller seeks somewhere else.
 */
class RootBlockDevice(
    private val path: String,
    override val blockSize: Int,
    override val blockCount: Long,
    override val name: String,
) : BlockDevice {

    private var reader: Process? = null
    private var readerOffset = -1L
    private var writer: Process? = null
    private var writerOffset = -1L

    override fun read(deviceOffset: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        requireAligned(deviceOffset, length)
        val stream = readerAt(deviceOffset)
        var read = 0
        while (read < length) {
            val n = stream.read(dst, dstOffset + read, length - read)
            if (n < 0) throw BlockDeviceException("$path ended before $length bytes could be read")
            read += n
        }
        readerOffset += length
    }

    override fun write(deviceOffset: Long, src: ByteArray, srcOffset: Int, length: Int) {
        requireAligned(deviceOffset, length)
        writerAt(deviceOffset).write(src, srcOffset, length)
        writerOffset += length
    }

    override fun flush() {
        closeWriter()
    }

    override fun close() {
        closeWriter()
        closeReader()
    }

    private fun readerAt(offset: Long): InputStream {
        if (reader == null || readerOffset != offset) {
            closeReader()
            reader = dd("dd if=$path bs=$blockSize skip=${offset / blockSize} 2>/dev/null")
            readerOffset = offset
        }
        return reader!!.inputStream
    }

    private fun writerAt(offset: Long): OutputStream {
        if (writer == null || writerOffset != offset) {
            closeWriter()
            writer = dd("dd of=$path bs=$blockSize seek=${offset / blockSize} conv=notrunc 2>/dev/null")
            writerOffset = offset
        }
        return writer!!.outputStream
    }

    private fun dd(command: String): Process =
        ProcessBuilder("su", "-c", command).start()

    private fun closeReader() {
        reader?.let { process ->
            runCatching { process.inputStream.close() }
            process.destroy()
        }
        reader = null
        readerOffset = -1
    }

    private fun closeWriter() {
        writer?.let { process ->
            runCatching { process.outputStream.close() }
            val exit = process.waitFor()
            if (exit != 0) throw BlockDeviceException("writing to $path failed (dd exited with $exit)")
        }
        writer = null
        writerOffset = -1
    }

    private fun requireAligned(offset: Long, length: Int) {
        if (offset % blockSize != 0L || length % blockSize != 0) {
            throw BlockDeviceException("unaligned access: offset=$offset length=$length")
        }
        if (offset + length > sizeBytes) throw BlockDeviceException("access past the end of $path")
    }
}
