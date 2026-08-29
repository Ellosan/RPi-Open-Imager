package dev.openimager.core.block

import java.io.File
import java.io.RandomAccessFile

/** A [BlockDevice] backed by a regular file. Used for tests and for "write to image file" flows. */
class FileBlockDevice(
    private val file: RandomAccessFile,
    override val blockSize: Int = 512,
    override val name: String = "file",
) : BlockDevice {

    constructor(file: File, blockSize: Int = 512) :
        this(RandomAccessFile(file, "rw"), blockSize, file.name)

    override val blockCount: Long = file.length() / blockSize

    override fun read(deviceOffset: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        requireAligned(deviceOffset, length)
        file.seek(deviceOffset)
        file.readFully(dst, dstOffset, length)
    }

    override fun write(deviceOffset: Long, src: ByteArray, srcOffset: Int, length: Int) {
        requireAligned(deviceOffset, length)
        file.seek(deviceOffset)
        file.write(src, srcOffset, length)
    }

    override fun flush() {
        file.fd.sync()
    }

    override fun close() {
        file.close()
    }
}
