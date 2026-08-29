package dev.openimager.core.util

/** Little-endian accessors shared by the MBR and FAT parsers. */

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.u16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

internal fun ByteArray.u32(index: Int): Long =
    u16(index).toLong() or (u16(index + 2).toLong() shl 16)

internal fun ByteArray.setU16(index: Int, value: Int) {
    this[index] = (value and 0xFF).toByte()
    this[index + 1] = ((value ushr 8) and 0xFF).toByte()
}

internal fun ByteArray.setU32(index: Int, value: Long) {
    setU16(index, (value and 0xFFFF).toInt())
    setU16(index + 2, ((value ushr 16) and 0xFFFF).toInt())
}

/** Big-endian accessors, used by SCSI command blocks. */
fun ByteArray.putBe32(index: Int, value: Long) {
    this[index] = ((value ushr 24) and 0xFF).toByte()
    this[index + 1] = ((value ushr 16) and 0xFF).toByte()
    this[index + 2] = ((value ushr 8) and 0xFF).toByte()
    this[index + 3] = (value and 0xFF).toByte()
}

fun ByteArray.putBe16(index: Int, value: Int) {
    this[index] = ((value ushr 8) and 0xFF).toByte()
    this[index + 1] = (value and 0xFF).toByte()
}

fun ByteArray.be32(index: Int): Long =
    (u8(index).toLong() shl 24) or (u8(index + 1).toLong() shl 16) or
        (u8(index + 2).toLong() shl 8) or u8(index + 3).toLong()

fun ByteArray.be64(index: Int): Long =
    (be32(index) shl 32) or be32(index + 4)

fun ByteArray.unsigned8(index: Int): Int = u8(index)

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
