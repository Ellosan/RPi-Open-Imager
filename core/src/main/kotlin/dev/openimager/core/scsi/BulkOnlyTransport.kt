package dev.openimager.core.scsi

import java.io.IOException

/**
 * The USB Mass Storage Bulk Only Transport wrappers.
 *
 * Every command travels as a 31 byte Command Block Wrapper, an optional data phase and a 13 byte
 * Command Status Wrapper. The layout lives here, away from the Android USB plumbing, so it can be
 * checked byte for byte in tests.
 */
object BulkOnlyTransport {

    const val CBW_LENGTH = 31
    const val CSW_LENGTH = 13

    /** "USBC" and "USBS", stored little endian. */
    private const val CBW_SIGNATURE = 0x43425355L
    private const val CSW_SIGNATURE = 0x53425355L

    const val STATUS_PASSED = 0
    const val STATUS_FAILED = 1
    const val STATUS_PHASE_ERROR = 2

    class ProtocolException(message: String) : IOException(message)

    /** Fills [buffer] with the command wrapper for [cdb]. */
    fun buildCommandBlock(
        buffer: ByteArray,
        tag: Int,
        dataTransferLength: Int,
        directionIn: Boolean,
        lun: Int,
        cdb: ByteArray,
    ) {
        require(buffer.size >= CBW_LENGTH) { "command block buffer is too small" }
        require(cdb.isNotEmpty() && cdb.size <= 16) { "a command descriptor block is 1 to 16 bytes" }
        java.util.Arrays.fill(buffer, 0, CBW_LENGTH, 0)
        putLe32(buffer, 0, CBW_SIGNATURE)
        putLe32(buffer, 4, tag.toLong() and 0xFFFFFFFFL)
        putLe32(buffer, 8, dataTransferLength.toLong())
        buffer[12] = if (directionIn) 0x80.toByte() else 0x00
        buffer[13] = (lun and 0x0F).toByte()
        buffer[14] = cdb.size.toByte()
        System.arraycopy(cdb, 0, buffer, 15, cdb.size)
    }

    data class Status(val tag: Int, val residue: Long, val status: Int) {
        val passed: Boolean get() = status == STATUS_PASSED
        val phaseError: Boolean get() = status == STATUS_PHASE_ERROR
    }

    /** Reads the status wrapper, rejecting anything that is not an answer to [expectedTag]. */
    fun parseStatus(buffer: ByteArray, expectedTag: Int): Status {
        if (buffer.size < CSW_LENGTH) throw ProtocolException("the status wrapper is too short")
        if (le32(buffer, 0) != CSW_SIGNATURE) throw ProtocolException("malformed status from the card reader")
        val tag = le32(buffer, 4).toInt()
        if (tag != expectedTag) throw ProtocolException("the card reader answered a different command")
        return Status(tag = tag, residue = le32(buffer, 8), status = buffer[12].toInt() and 0xFF)
    }

    private fun putLe32(buffer: ByteArray, index: Int, value: Long) {
        for (i in 0 until 4) buffer[index + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun le32(buffer: ByteArray, index: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = value or ((buffer[index + i].toLong() and 0xFF) shl (8 * i))
        return value
    }
}
