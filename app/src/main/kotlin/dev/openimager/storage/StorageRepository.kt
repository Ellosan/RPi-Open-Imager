package dev.openimager.storage

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dev.openimager.core.block.BlockDevice
import dev.openimager.root.RootBlockDevice
import dev.openimager.root.RootShell
import dev.openimager.usb.UsbBlockDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Finds the cards the phone can reach and hands out an open [BlockDevice] for the chosen one. */
class StorageRepository(private val context: Context) {

    private val usbManager: UsbManager = context.getSystemService(UsbManager::class.java)

    /** Emits whenever a USB device is plugged in or pulled out. */
    fun deviceChanges(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(Unit)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    suspend fun list(includeRootDevices: Boolean): List<StorageTarget> = withContext(Dispatchers.IO) {
        val usb = usbManager.deviceList.values
            .filter { UsbBlockDevice.findInterface(it) != null }
            .map { device -> describe(device) }

        val root = if (includeRootDevices && RootShell.isAvailable()) {
            runCatching { RootShell.listRemovableDevices().map(StorageTarget::Root) }
                .onFailure { Log.w(TAG, "listing root devices failed", it) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

        usb + root
    }

    /** Capacity and product name come from the device itself, so it has to be opened briefly. */
    private fun describe(device: UsbDevice): StorageTarget.Usb {
        val fallbackName = listOfNotNull(device.manufacturerName, device.productName)
            .joinToString(" ")
            .ifEmpty { "USB storage" }

        if (!usbManager.hasPermission(device)) {
            return StorageTarget.Usb(device, fallbackName, 0, hasPermission = false)
        }
        return try {
            UsbBlockDevice.open(usbManager, device).use { block ->
                StorageTarget.Usb(device, block.name, block.sizeBytes, hasPermission = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not probe ${device.deviceName}", e)
            StorageTarget.Usb(device, fallbackName, 0, hasPermission = true, error = e.message)
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (continuation.isActive) continuation.resume(granted)
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags,
            )
            usbManager.requestPermission(device, intent)
            continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    /** Opens the device for writing. The caller owns the result and must close it. */
    fun open(target: StorageTarget): BlockDevice = when (target) {
        is StorageTarget.Usb -> UsbBlockDevice.open(usbManager, target.device)
        is StorageTarget.Root -> {
            RootShell.unmountPartitions(target.raw.path)
            RootBlockDevice(
                path = target.raw.path,
                blockSize = target.raw.blockSize,
                blockCount = target.raw.sizeBytes / target.raw.blockSize,
                name = target.raw.label,
            )
        }
    }

    /** Re-resolves a target against the devices currently attached, after a service restart. */
    suspend fun resolve(id: String, includeRootDevices: Boolean): StorageTarget? =
        list(includeRootDevices).firstOrNull { it.id == id }

    companion object {
        private const val TAG = "StorageRepository"
        const val ACTION_USB_PERMISSION = "dev.openimager.USB_PERMISSION"
    }
}
