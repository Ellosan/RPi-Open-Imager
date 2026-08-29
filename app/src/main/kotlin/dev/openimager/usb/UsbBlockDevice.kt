package dev.openimager.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import dev.openimager.core.block.BlockDevice
import dev.openimager.core.block.BlockDeviceException
import dev.openimager.core.scsi.BulkOnlyTransport
import dev.openimager.core.scsi.ScsiCommands
import dev.openimager.core.util.be32
import dev.openimager.core.util.be64
import java.util.concurrent.atomic.AtomicInteger

/**
 * A USB mass storage device driven directly over bulk endpoints, which is the only way an
 * unrooted Android phone can write raw sectors to an SD card.
 *
 * Implements the USB Bulk Only Transport: a 31 byte command wrapper, an optional data phase and a
 * 13 byte status wrapper, carrying the SCSI commands in [ScsiCommands].
 */
class UsbBlockDevice private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private var lun: Int,
    override val blockSize: Int,
    override val blockCount: Long,
    override val name: String,
    private val useLongCommands: Boolean,
) : BlockDevice {

    private val tagCounter = AtomicInteger(1)
    private val cbw = ByteArray(BulkOnlyTransport.CBW_LENGTH)
    private val csw = ByteArray(BulkOnlyTransport.CSW_LENGTH)
    private var closed = false

    /** Blocks per SCSI command: 64 KiB keeps the bus busy without tripping short timeouts. */
    private val maxBlocksPerCommand = maxOf(1, MAX_COMMAND_BYTES / blockSize)

    override fun read(deviceOffset: Long, dst: ByteArray, dstOffset: Int, length: Int) =
        transferBlocks(deviceOffset, dst, dstOffset, length, reading = true)

    override fun write(deviceOffset: Long, src: ByteArray, srcOffset: Int, length: Int) =
        transferBlocks(deviceOffset, src, srcOffset, length, reading = false)

    override fun flush() {
        // Best effort: plenty of readers do not implement SYNCHRONIZE CACHE and stall instead.
        runCatching { command(ScsiCommands.synchronizeCache10(), null, 0, 0, directionIn = false) }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }

    private fun transferBlocks(deviceOffset: Long, buffer: ByteArray, bufferOffset: Int, length: Int, reading: Boolean) {
        check(!closed) { "device is closed" }
        if (deviceOffset % blockSize != 0L || length % blockSize != 0) {
            throw BlockDeviceException("unaligned transfer: offset=$deviceOffset length=$length")
        }
        if (deviceOffset + length > sizeBytes) {
            throw BlockDeviceException("transfer past the end of ${name}")
        }

        var lba = deviceOffset / blockSize
        var remaining = length / blockSize
        var offset = bufferOffset
        while (remaining > 0) {
            val blocks = minOf(remaining, maxBlocksPerCommand)
            val bytes = blocks * blockSize
            val cdb = when {
                reading && useLongCommands -> ScsiCommands.read16(lba, blocks)
                reading -> ScsiCommands.read10(lba, blocks)
                useLongCommands -> ScsiCommands.write16(lba, blocks)
                else -> ScsiCommands.write10(lba, blocks)
            }
            command(cdb, buffer, offset, bytes, directionIn = reading)
            lba += blocks
            remaining -= blocks
            offset += bytes
        }
    }

    /** Runs one SCSI command, retrying once after a recoverable condition such as a bus reset. */
    @Synchronized
    private fun command(
        cdb: ByteArray,
        data: ByteArray?,
        dataOffset: Int,
        dataLength: Int,
        directionIn: Boolean,
        allowRetry: Boolean = true,
    ): Int {
        val tag = tagCounter.getAndIncrement()
        BulkOnlyTransport.buildCommandBlock(cbw, tag, dataLength, directionIn, lun, cdb)

        if (bulk(outEndpoint, cbw, 0, cbw.size) != cbw.size) {
            reset()
            if (allowRetry) return command(cdb, data, dataOffset, dataLength, directionIn, allowRetry = false)
            throw BlockDeviceException("the card reader did not accept a command")
        }

        var transferred = 0
        if (dataLength > 0 && data != null) {
            val endpoint = if (directionIn) inEndpoint else outEndpoint
            transferred = bulk(endpoint, data, dataOffset, dataLength)
            if (transferred < 0) {
                clearHalt(endpoint)
                transferred = 0
            }
        }

        val status = readStatus(tag)
        when (status) {
            BulkOnlyTransport.STATUS_PASSED -> return transferred
            BulkOnlyTransport.STATUS_FAILED -> {
                val sense = requestSense()
                if (allowRetry && ScsiCommands.senseKey(sense) == SENSE_UNIT_ATTENTION) {
                    return command(cdb, data, dataOffset, dataLength, directionIn, allowRetry = false)
                }
                throw BlockDeviceException(ScsiCommands.describeSense(sense))
            }
            else -> {
                reset()
                throw BlockDeviceException("the card reader lost sync and had to be reset")
            }
        }
    }

    private fun readStatus(expectedTag: Int): Int {
        var read = bulk(inEndpoint, csw, 0, csw.size)
        if (read != csw.size) {
            // A stalled status phase is recoverable: clear the halt and ask again.
            clearHalt(inEndpoint)
            read = bulk(inEndpoint, csw, 0, csw.size)
        }
        if (read != csw.size) throw BlockDeviceException("the card reader stopped responding")
        return try {
            BulkOnlyTransport.parseStatus(csw, expectedTag).status
        } catch (e: BulkOnlyTransport.ProtocolException) {
            throw BlockDeviceException(e.message ?: "the card reader sent an unusable status")
        }
    }

    private fun requestSense(): ByteArray {
        val sense = ByteArray(ScsiCommands.REQUEST_SENSE_LENGTH)
        return try {
            command(ScsiCommands.requestSense(), sense, 0, sense.size, directionIn = true, allowRetry = false)
            sense
        } catch (e: BlockDeviceException) {
            Log.w(TAG, "REQUEST SENSE failed", e)
            sense
        }
    }

    private fun bulk(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int): Int {
        var transferred = 0
        while (transferred < length) {
            val chunk = minOf(MAX_BULK_TRANSFER, length - transferred)
            val moved = connection.bulkTransfer(endpoint, buffer, offset + transferred, chunk, TIMEOUT_MS)
            if (moved < 0) return if (transferred > 0) transferred else -1
            transferred += moved
            if (moved < chunk) break // short packet: the device ended the transfer early
        }
        return transferred
    }

    private fun clearHalt(endpoint: UsbEndpoint) {
        connection.controlTransfer(0x02, 0x01, 0, endpoint.address, null, 0, TIMEOUT_MS)
    }

    private fun reset() {
        connection.controlTransfer(0x21, 0xFF, 0, usbInterface.id, null, 0, TIMEOUT_MS)
        clearHalt(inEndpoint)
        clearHalt(outEndpoint)
    }

    companion object {
        private const val TAG = "UsbBlockDevice"
        private const val SENSE_UNIT_ATTENTION = 0x06
        private const val TIMEOUT_MS = 20_000
        private const val MAX_BULK_TRANSFER = 16 * 1024
        private const val MAX_COMMAND_BYTES = 64 * 1024
        private const val SUBCLASS_SCSI = 0x06
        private const val PROTOCOL_BULK_ONLY = 0x50

        /** The mass storage interface of [device], or null when it is some other kind of gadget. */
        fun findInterface(device: UsbDevice): UsbInterface? {
            for (index in 0 until device.interfaceCount) {
                val candidate = device.getInterface(index)
                if (candidate.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    candidate.interfaceSubclass == SUBCLASS_SCSI &&
                    candidate.interfaceProtocol == PROTOCOL_BULK_ONLY
                ) {
                    return candidate
                }
            }
            return null
        }

        fun open(manager: UsbManager, device: UsbDevice): UsbBlockDevice {
            val usbInterface = findInterface(device)
                ?: throw BlockDeviceException("${device.productName ?: "the device"} is not a USB mass storage device")

            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (index in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(index)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = input ?: endpoint
                else output = output ?: endpoint
            }
            if (input == null || output == null) throw BlockDeviceException("the card reader has no bulk endpoints")

            val connection = manager.openDevice(device)
                ?: throw BlockDeviceException("permission to use the card reader was refused")
            try {
                if (!connection.claimInterface(usbInterface, true)) {
                    throw BlockDeviceException("another app is already using the card reader")
                }
                return probe(connection, usbInterface, input, output, device)
            } catch (e: Throwable) {
                runCatching { connection.releaseInterface(usbInterface) }
                connection.close()
                throw e
            }
        }

        private fun probe(
            connection: UsbDeviceConnection,
            usbInterface: UsbInterface,
            input: UsbEndpoint,
            output: UsbEndpoint,
            device: UsbDevice,
        ): UsbBlockDevice {
            // A block size of 512 is assumed only until READ CAPACITY answers.
            val probe = UsbBlockDevice(connection, usbInterface, input, output, 0, 512, 0, "", false)
            val lun = selectReadyLun(probe, maxLun(connection, usbInterface))
            probe.lun = lun

            val inquiry = ByteArray(ScsiCommands.INQUIRY_LENGTH)
            probe.command(ScsiCommands.inquiry(), inquiry, 0, inquiry.size, directionIn = true)
            val vendor = String(inquiry, 8, 8, Charsets.US_ASCII).trim()
            val product = String(inquiry, 16, 16, Charsets.US_ASCII).trim()
            val name = listOf(vendor, product).filter { it.isNotEmpty() }.joinToString(" ")
                .ifEmpty { device.productName ?: "USB storage" }

            val capacity = ByteArray(ScsiCommands.READ_CAPACITY_10_LENGTH)
            probe.command(ScsiCommands.readCapacity10(), capacity, 0, capacity.size, directionIn = true)
            var lastBlock = capacity.be32(0)
            var blockSize = capacity.be32(4).toInt()
            var useLongCommands = false

            if (lastBlock == 0xFFFFFFFFL) {
                val capacity16 = ByteArray(ScsiCommands.READ_CAPACITY_16_LENGTH)
                probe.command(ScsiCommands.readCapacity16(), capacity16, 0, capacity16.size, directionIn = true)
                lastBlock = capacity16.be64(0)
                blockSize = capacity16.be32(8).toInt()
                useLongCommands = true
            }
            if (blockSize <= 0 || blockSize % 512 != 0) {
                throw BlockDeviceException("$name reports an unusable sector size of $blockSize bytes")
            }

            val mode = ByteArray(ScsiCommands.MODE_SENSE_6_LENGTH)
            val writeProtected = runCatching {
                probe.command(ScsiCommands.modeSense6(), mode, 0, mode.size, directionIn = true)
                mode[2].toInt() and 0x80 != 0
            }.getOrDefault(false)
            if (writeProtected) {
                throw BlockDeviceException("$name is write protected - check the lock switch on the card")
            }

            return UsbBlockDevice(
                connection = connection,
                usbInterface = usbInterface,
                inEndpoint = input,
                outEndpoint = output,
                lun = lun,
                blockSize = blockSize,
                blockCount = lastBlock + 1,
                name = name,
                useLongCommands = useLongCommands,
            )
        }

        /**
         * Picks the logical unit that actually holds a card. Multi slot readers expose one LUN per
         * slot, and a reader reports NOT READY for a moment after a card is inserted.
         */
        private fun selectReadyLun(device: UsbBlockDevice, maxLun: Int): Int {
            var lastError: BlockDeviceException? = null
            repeat(10) {
                for (candidate in 0..maxLun) {
                    device.lun = candidate
                    try {
                        device.command(ScsiCommands.testUnitReady(), null, 0, 0, directionIn = false)
                        return candidate
                    } catch (e: BlockDeviceException) {
                        lastError = e
                    }
                }
                Thread.sleep(300)
            }
            throw lastError ?: BlockDeviceException("no card was found in the reader")
        }

        private fun maxLun(connection: UsbDeviceConnection, usbInterface: UsbInterface): Int {
            val buffer = ByteArray(1)
            val result = connection.controlTransfer(0xA1, 0xFE, 0, usbInterface.id, buffer, 1, 2_000)
            // Readers that do not implement GET MAX LUN stall, which means a single unit.
            return if (result == 1) (buffer[0].toInt() and 0x0F) else 0
        }

    }
}
