package dev.openimager.core.block

/** A window onto [base] starting at [firstBlock], used to address a single MBR partition. */
class PartitionBlockDevice(
    private val base: BlockDevice,
    private val firstBlock: Long,
    override val blockCount: Long,
    override val name: String = base.name,
) : BlockDevice {

    override val blockSize: Int get() = base.blockSize

    private val offset: Long get() = firstBlock * blockSize

    override fun read(deviceOffset: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        requireAligned(deviceOffset, length)
        base.read(offset + deviceOffset, dst, dstOffset, length)
    }

    override fun write(deviceOffset: Long, src: ByteArray, srcOffset: Int, length: Int) {
        requireAligned(deviceOffset, length)
        base.write(offset + deviceOffset, src, srcOffset, length)
    }

    override fun flush() = base.flush()

    /** Closing a partition view must not close the underlying device. */
    override fun close() = Unit
}
