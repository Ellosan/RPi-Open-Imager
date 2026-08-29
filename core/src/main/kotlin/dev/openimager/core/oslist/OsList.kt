package dev.openimager.core.oslist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Model of the `os_list_imagingutility_v4.json` catalogue published by Raspberry Pi. Every field is
 * optional: the catalogue mixes downloadable entries, nested categories and remote sub lists, and
 * gains new keys over time.
 */
@Serializable
data class OsListDocument(
    val imager: ImagerMetadata? = null,
    @SerialName("os_list") val osList: List<OsListItem> = emptyList(),
)

@Serializable
data class ImagerMetadata(
    @SerialName("latest_version") val latestVersion: String? = null,
    @SerialName("default_os") val defaultOs: String? = null,
    val devices: List<HardwareDevice> = emptyList(),
)

/** One entry of the "Raspberry Pi Device" filter. */
@Serializable
data class HardwareDevice(
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("matching_type") val matchingType: String? = null,
) {
    /** `exclusive` devices hide images that do not name them; the rest only reorder the list. */
    val isExclusive: Boolean get() = matchingType.equals("exclusive", ignoreCase = true)
}

@Serializable
data class OsListItem(
    val name: String = "",
    val description: String? = null,
    val icon: String? = null,
    val url: String? = null,
    val website: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("extract_size") val extractSize: Long = 0,
    @SerialName("extract_sha256") val extractSha256: String? = null,
    @SerialName("image_download_size") val downloadSize: Long = 0,
    @SerialName("image_download_sha256") val downloadSha256: String? = null,
    @SerialName("init_format") val initFormat: String? = null,
    val devices: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val random: Boolean = false,
    val subitems: List<OsListItem> = emptyList(),
    @SerialName("subitems_url") val subitemsUrl: String? = null,
) {
    /** True when this entry points at an image that can be written. */
    val isImage: Boolean get() = !url.isNullOrBlank()

    /** True when selecting this entry opens another list rather than starting a write. */
    val isCategory: Boolean get() = !isImage && (subitems.isNotEmpty() || !subitemsUrl.isNullOrBlank())

    fun matches(device: HardwareDevice?): Boolean {
        if (device == null || device.tags.isEmpty()) return true
        if (devices.isEmpty()) return !device.isExclusive || isCategory
        return devices.any { it in device.tags }
    }
}

/** Applies the "Raspberry Pi Device" filter, keeping categories that still have matching children. */
fun List<OsListItem>.filterForDevice(device: HardwareDevice?): List<OsListItem> {
    if (device == null || device.tags.isEmpty()) return this
    return mapNotNull { item ->
        when {
            item.subitems.isNotEmpty() -> {
                val children = item.subitems.filterForDevice(device)
                if (children.isEmpty()) null else item.copy(subitems = children)
            }
            item.isCategory -> item
            item.matches(device) -> item
            else -> null
        }
    }
}

object OsListParser {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun parseDocument(text: String): OsListDocument = json.decodeFromString(OsListDocument.serializer(), text)

    /** Sub lists are served in the same shape as the root document. */
    fun parseSubList(text: String): List<OsListItem> = parseDocument(text).osList
}
