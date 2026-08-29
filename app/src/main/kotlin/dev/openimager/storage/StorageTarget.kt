package dev.openimager.storage

import android.hardware.usb.UsbDevice
import dev.openimager.root.RootShell

/** Something the image can be written to, as shown in the storage picker. */
sealed interface StorageTarget {
    val id: String
    val label: String
    val sizeBytes: Long
    val subtitle: String

    data class Usb(
        val device: UsbDevice,
        override val label: String,
        override val sizeBytes: Long,
        val hasPermission: Boolean,
        val error: String? = null,
    ) : StorageTarget {
        override val id: String get() = "usb:${device.deviceName}"
        override val subtitle: String
            get() = when {
                error != null -> error
                !hasPermission -> "Tap to grant access to this reader"
                else -> "USB - ${formatCapacity(sizeBytes)}"
            }
    }

    data class Root(val raw: RootShell.RawDevice) : StorageTarget {
        override val id: String get() = "root:${raw.path}"
        override val label: String get() = raw.label
        override val sizeBytes: Long get() = raw.sizeBytes
        override val subtitle: String
            get() = "${if (raw.isCardSlot) "Card slot" else "Block device"} " +
                "${raw.path} - ${formatCapacity(sizeBytes)}"
    }

    companion object {
        fun formatCapacity(bytes: Long): String {
            if (bytes <= 0) return "unknown size"
            val units = listOf("kB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1000
            var unit = 0
            while (value >= 1000 && unit < units.lastIndex) {
                value /= 1000
                unit++
            }
            return if (value >= 100) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
        }
    }
}
