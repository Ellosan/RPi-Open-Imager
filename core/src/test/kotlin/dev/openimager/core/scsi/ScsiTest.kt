package dev.openimager.core.scsi

import dev.openimager.core.util.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte level checks of the USB mass storage wire format. A card reader is unforgiving here: a
 * misplaced field means a stalled endpoint or, worse, sectors written to the wrong place.
 */
class ScsiTest {

    @Test
    fun `command wrapper matches the bulk only transport layout`() {
        val buffer = ByteArray(BulkOnlyTransport.CBW_LENGTH)
        BulkOnlyTransport.buildCommandBlock(
            buffer = buffer,
            tag = 0x11223344,
            dataTransferLength = 0x10000,
            directionIn = false,
            lun = 1,
            cdb = ScsiCommands.write10(lba = 0x01020304, blocks = 128),
        )

        assertEquals("USBC", String(buffer, 0, 4, Charsets.US_ASCII))
        assertEquals("44332211", buffer.copyOfRange(4, 8).toHex()) // tag, little endian
        assertEquals("00000100", buffer.copyOfRange(8, 12).toHex()) // 64 KiB, little endian
        assertEquals(0x00, buffer[12].toInt()) // host to device
        assertEquals(0x01, buffer[13].toInt()) // LUN 1
        assertEquals(10, buffer[14].toInt()) // WRITE(10) is ten bytes
        assertEquals("2a000102030400008000", buffer.copyOfRange(15, 25).toHex())
    }

    @Test
    fun `a device to host command sets the direction bit`() {
        val buffer = ByteArray(BulkOnlyTransport.CBW_LENGTH)
        BulkOnlyTransport.buildCommandBlock(buffer, 1, 36, true, 0, ScsiCommands.inquiry())
        assertEquals(0x80, buffer[12].toInt() and 0xFF)
    }

    @Test
    fun `status wrapper is parsed and checked against its command`() {
        val status = ByteArray(BulkOnlyTransport.CSW_LENGTH)
        "USBS".toByteArray(Charsets.US_ASCII).copyInto(status)
        // tag 0x11223344, residue 512, status FAILED
        listOf(0x44, 0x33, 0x22, 0x11).forEachIndexed { i, b -> status[4 + i] = b.toByte() }
        status[8] = 0x00
        status[9] = 0x02
        status[12] = 1

        val parsed = BulkOnlyTransport.parseStatus(status, 0x11223344)
        assertEquals(512L, parsed.residue)
        assertEquals(BulkOnlyTransport.STATUS_FAILED, parsed.status)

        assertThrows(BulkOnlyTransport.ProtocolException::class.java) {
            BulkOnlyTransport.parseStatus(status, 0x55667788)
        }
        status[0] = 'X'.code.toByte()
        assertThrows(BulkOnlyTransport.ProtocolException::class.java) {
            BulkOnlyTransport.parseStatus(status, 0x11223344)
        }
    }

    @Test
    fun `read and write command blocks carry big endian addresses`() {
        assertEquals("28000000000a00000100", ScsiCommands.read10(lba = 10, blocks = 1).toHex())
        assertEquals("2a00ffffffff00ffff00", ScsiCommands.write10(lba = 0xFFFFFFFFL, blocks = 65535).toHex())
        // READ(16) addresses beyond 2 TiB, where a 32 bit LBA runs out.
        assertEquals(
            "8800" + "0000000100000002" + "00000080" + "0000",
            ScsiCommands.read16(lba = 0x1_00000002L, blocks = 128).toHex(),
        )
        assertEquals("9e10" + "00".repeat(8) + "00000020" + "0000", ScsiCommands.readCapacity16().toHex())
    }

    @Test
    fun `sense data is turned into something a person can act on`() {
        fun sense(key: Int, asc: Int = 0, ascq: Int = 0) = ByteArray(18).also {
            it[0] = 0x70
            it[2] = key.toByte()
            it[12] = asc.toByte()
            it[13] = ascq.toByte()
        }

        assertTrue(ScsiCommands.describeSense(sense(0x07)).contains("write protected"))
        assertTrue(ScsiCommands.describeSense(sense(0x02, asc = 0x3A)).contains("no card"))
        assertTrue(ScsiCommands.describeSense(sense(0x03)).contains("media error"))
        assertEquals(0x06, ScsiCommands.senseKey(sense(0x06)))
        assertTrue(ScsiCommands.describeSense(sense(0x0B, 0x12, 0x34)).contains("0x0B"))
    }
}
