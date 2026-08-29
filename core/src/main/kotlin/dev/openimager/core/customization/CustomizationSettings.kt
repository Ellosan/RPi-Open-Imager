package dev.openimager.core.customization

import kotlinx.serialization.Serializable

/** How an image expects its first boot settings to be delivered. */
enum class InitFormat {
    /** `firstrun.sh` driven by a `systemd.run=` kernel argument - Raspberry Pi OS up to Bookworm. */
    SYSTEMD,

    /** cloud-init `user-data` and `network-config`, used by Ubuntu and current Raspberry Pi OS. */
    CLOUD_INIT,

    /** The image declares that it cannot be customised. */
    NONE,
    ;

    companion object {
        /** Maps the `init_format` field of the OS catalogue. */
        fun fromCatalogue(value: String?): InitFormat = when (value?.lowercase()) {
            "cloudinit", "cloudinit-rpi" -> CLOUD_INIT
            "none" -> NONE
            "systemd" -> SYSTEMD
            // Unknown or missing: firstrun.sh is the safe default, it is a no-op on images that
            // never read the kernel argument we add.
            else -> SYSTEMD
        }
    }
}

/**
 * The "OS customisation" sheet. Secrets are kept in the form the target expects - a `$6$` crypt
 * hash and a derived WPA PSK - so no plain text password is ever stored or written to the card.
 */
@Serializable
data class CustomizationSettings(
    val enabled: Boolean = false,
    val hostname: String = "",
    val username: String = "",
    val passwordCrypt: String = "",
    val wifiSsid: String = "",
    val wifiPsk: String = "",
    val wifiHidden: Boolean = false,
    val wifiCountry: String = "",
    val timezone: String = "",
    val keyboardLayout: String = "",
    val enableSsh: Boolean = false,
    val sshAuthorizedKeys: String = "",
    val sshPasswordAuthentication: Boolean = true,
    val playSoundWhenDone: Boolean = true,
    val ejectWhenDone: Boolean = true,
) {
    val hasUser: Boolean get() = username.isNotBlank() && passwordCrypt.isNotBlank()
    val hasWifi: Boolean get() = wifiSsid.isNotBlank()
    val hasSshKeys: Boolean get() = sshAuthorizedKeys.isNotBlank()

    /** True when there is anything worth writing to the boot partition. */
    val hasAnyImageChange: Boolean
        get() = enabled && (
            hostname.isNotBlank() || hasUser || hasWifi || enableSsh ||
                timezone.isNotBlank() || keyboardLayout.isNotBlank()
            )

    val sshKeyList: List<String>
        get() = sshAuthorizedKeys.lines().map(String::trim).filter { it.isNotEmpty() }
}
