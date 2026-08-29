package dev.openimager.root

import android.util.Log
import java.io.IOException

/**
 * Optional support for phones with a built in card slot, where the card is a `/dev/block` node
 * that only root can write. Everything here is off unless the user turns it on in settings.
 */
object RootShell {

    private const val TAG = "RootShell"

    data class RawDevice(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val blockSize: Int,
        val model: String,
    ) {
        /** `mmcblk*` is the phone's own card slot; anything else came in over USB. */
        val isCardSlot: Boolean get() = name.startsWith("mmcblk")

        val label: String
            get() = when {
                isCardSlot && model.isNotEmpty() && model != name -> "SD card ($model)"
                isCardSlot -> "SD card"
                else -> model
            }
    }

    fun isAvailable(): Boolean = runCatching {
        run("id").contains("uid=0")
    }.getOrDefault(false)

    /**
     * Removable block devices only. The internal eMMC reports `removable` as 0, which keeps the
     * phone's own storage out of the picker.
     */
    fun listRemovableDevices(): List<RawDevice> {
        val script = """
            for dir in /sys/block/*; do
              name=${'$'}(basename ${'$'}dir)
              removable=${'$'}(cat ${'$'}dir/removable 2>/dev/null)
              sectors=${'$'}(cat ${'$'}dir/size 2>/dev/null)
              logical=${'$'}(cat ${'$'}dir/queue/logical_block_size 2>/dev/null)
              model=${'$'}(cat ${'$'}dir/device/name 2>/dev/null || cat ${'$'}dir/device/model 2>/dev/null)
              echo "${'$'}name|${'$'}removable|${'$'}sectors|${'$'}logical|${'$'}model"
            done
        """.trimIndent()

        return run(script).lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 5) return@mapNotNull null
            val name = parts[0].trim()
            val removable = parts[1].trim() == "1"
            val sectors = parts[2].trim().toLongOrNull() ?: return@mapNotNull null
            val logical = parts[3].trim().toIntOrNull() ?: 512
            if (!removable || sectors <= 0 || IGNORED.any { name.startsWith(it) }) return@mapNotNull null
            RawDevice(
                name = name,
                path = "/dev/block/$name",
                // /sys/block/*/size is always in 512 byte units, whatever the logical block size is.
                sizeBytes = sectors * 512,
                blockSize = logical,
                model = parts[4].trim().ifEmpty { name },
            )
        }.toList()
    }

    /**
     * Android mounts a card as soon as it is inserted, and writing sectors underneath a mounted
     * filesystem corrupts both. Unmount every partition of the device first.
     */
    fun unmountPartitions(path: String) {
        val script = """
            for mounted in ${'$'}(grep "^$path" /proc/mounts | cut -d' ' -f1); do
              umount "${'$'}mounted" 2>/dev/null || umount -l "${'$'}mounted" 2>/dev/null
            done
            exit 0
        """.trimIndent()
        runCatching { run(script) }.onFailure { Log.w(TAG, "could not unmount $path", it) }
    }

    fun run(command: String): String {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            Log.w(TAG, "su command failed ($exit): $output")
            throw IOException("root command failed: ${output.take(200)}")
        }
        return output
    }

    private val IGNORED = listOf("loop", "ram", "zram", "dm-", "sr", "md")
}
