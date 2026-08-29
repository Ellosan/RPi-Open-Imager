package dev.openimager.core.customization

import dev.openimager.core.block.BlockDevice
import dev.openimager.core.block.PartitionBlockDevice
import dev.openimager.core.block.PartitionTable
import dev.openimager.core.fat.FatFileSystem
import java.io.IOException

class CustomizationException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class AppliedCustomization(
    val initFormat: InitFormat,
    val filesWritten: List<String>,
    val cmdlineArguments: List<String>,
)

/**
 * Applies the first boot settings to a freshly written card by editing the FAT boot partition in
 * place, the same way Raspberry Pi Imager does once the image has been streamed out.
 */
object CustomizationApplier {

    fun apply(
        disk: BlockDevice,
        settings: CustomizationSettings,
        declaredFormat: InitFormat?,
    ): AppliedCustomization {
        val partition = PartitionTable.findBootPartition(disk)
            ?: throw CustomizationException("no FAT boot partition found on the written image")
        val bootDevice = PartitionBlockDevice(disk, partition.firstBlock, partition.blockCount)
        val fat = try {
            FatFileSystem.read(bootDevice)
        } catch (e: IOException) {
            throw CustomizationException("the boot partition could not be read: ${e.message}", e)
        }

        val format = declaredFormat ?: detectInitFormat(fat)
        val customization = BootFileGenerator.build(settings, format)
        if (customization.files.isEmpty() && customization.cmdlineArguments.isEmpty()) {
            return AppliedCustomization(format, emptyList(), emptyList())
        }

        for (file in customization.files) {
            fat.writeFile(file.name, file.content.toByteArray(Charsets.UTF_8))
        }
        if (customization.cmdlineArguments.isNotEmpty()) {
            fat.writeFile("cmdline.txt", patchCmdline(fat, customization.cmdlineArguments).toByteArray(Charsets.UTF_8))
        }
        disk.flush()
        return AppliedCustomization(format, customization.files.map { it.name }, customization.cmdlineArguments)
    }

    /**
     * Mirrors the guess Raspberry Pi Imager makes for images that do not declare an init format:
     * an existing `user-data` means cloud-init, a pi-gen `issue.txt` means the firstrun script.
     */
    fun detectInitFormat(fat: FatFileSystem): InitFormat {
        if (fat.exists("user-data")) return InitFormat.CLOUD_INIT
        val issue = fat.readFile("issue.txt")?.toString(Charsets.UTF_8).orEmpty()
        if (issue.contains("pi-gen")) return InitFormat.SYSTEMD
        // Writing a cloud-init file does no harm on images that ignore it.
        return InitFormat.CLOUD_INIT
    }

    /** Appends kernel arguments to cmdline.txt, replacing any this app added on an earlier write. */
    internal fun patchCmdline(fat: FatFileSystem, arguments: List<String>): String {
        val existing = fat.readFile("cmdline.txt")?.toString(Charsets.UTF_8).orEmpty()
        val kept = existing.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .filterNot { argument -> arguments.any { argument.substringBefore('=') == it.substringBefore('=') } }
        return (kept + arguments).joinToString(" ") + "\n"
    }
}
