package dev.openimager.usb

import dev.openimager.core.util.putBe16
import dev.openimager.core.util.putBe32

/**
 * The handful of SCSI Block Commands a card writer needs, as raw command descriptor blocks.
 * Everything travels inside the USB Bulk Only Transport wrapper built by [UsbBlockDevice].
 */
internal object ScsiCommands {

    const val INQUIRY_LENGTH = 36
    const val REQUEST_SENSE_LENGTH = 18
    const val READ_CAPACITY_10_LENGTH = 8
    const val READ_CAPACITY_16_LENGTH = 32
    const val MODE_SENSE_6_LENGTH = 4

    fun testUnitReady(): ByteArray = ByteArray(6)

    fun inquiry(): ByteArray = ByteArray(6).apply {
        this[0] = 0x12
        this[4] = INQUIRY_LENGTH.toByte()
    }

    fun requestSense(): ByteArray = ByteArray(6).apply {
        this[0] = 0x03
        this[4] = REQUEST_SENSE_LENGTH.toByte()
    }

    fun readCapacity10(): ByteArray = ByteArray(10).apply { this[0] = 0x25 }

    fun readCapacity16(): ByteArray = ByteArray(16).apply {
        this[0] = 0x9E.toByte()
        this[1] = 0x10 // SERVICE ACTION IN: READ CAPACITY(16)
        putBe32(10, READ_CAPACITY_16_LENGTH.toLong())
    }

    /** Page 0x3F of MODE SENSE(6) carries the write protect bit in its header. */
    fun modeSense6(): ByteArray = ByteArray(6).apply {
        this[0] = 0x1A
        this[2] = 0x3F
        this[4] = MODE_SENSE_6_LENGTH.toByte()
    }

    fun read10(lba: Long, blocks: Int): ByteArray = ByteArray(10).apply {
        this[0] = 0x28
        putBe32(2, lba)
        putBe16(7, blocks)
    }

    fun write10(lba: Long, blocks: Int): ByteArray = ByteArray(10).apply {
        this[0] = 0x2A
        putBe32(2, lba)
        putBe16(7, blocks)
    }

    fun read16(lba: Long, blocks: Int): ByteArray = ByteArray(16).apply {
        this[0] = 0x88.toByte()
        putBe32(2, (lba ushr 32))
        putBe32(6, lba and 0xFFFFFFFFL)
        putBe32(10, blocks.toLong())
    }

    fun write16(lba: Long, blocks: Int): ByteArray = ByteArray(16).apply {
        this[0] = 0x8A.toByte()
        putBe32(2, (lba ushr 32))
        putBe32(6, lba and 0xFFFFFFFFL)
        putBe32(10, blocks.toLong())
    }

    fun synchronizeCache10(): ByteArray = ByteArray(10).apply { this[0] = 0x35 }

    /** Turns the sense data of a failed command into something worth showing a person. */
    fun describeSense(sense: ByteArray): String {
        if (sense.size < 14) return "the card reader reported an unspecified error"
        val key = sense[2].toInt() and 0x0F
        val asc = sense[12].toInt() and 0xFF
        val ascq = sense[13].toInt() and 0xFF
        return when {
            key == 0x07 -> "the card is write protected - check the lock switch on the adapter"
            key == 0x02 && asc == 0x3A -> "there is no card in the reader"
            key == 0x02 -> "the card reader is not ready"
            key == 0x06 -> "the card was swapped or reset during the operation"
            key == 0x03 -> "the card reported a media error - it may be failing"
            key == 0x05 -> "the card reader rejected the command"
            else -> "SCSI error (sense key 0x%X, asc 0x%02X, ascq 0x%02X)".format(key, asc, ascq)
        }
    }

    fun senseKey(sense: ByteArray): Int = if (sense.size > 2) sense[2].toInt() and 0x0F else 0
}
