package dev.openimager.core.customization

import dev.openimager.core.util.toHex
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Derives the 256 bit WPA PSK so the card carries the hash instead of the Wi-Fi passphrase. */
object WpaPsk {

    fun derive(ssid: String, passphrase: String): String {
        // A 64 character hex string is already a PSK; passing it through PBKDF2 would break it.
        if (isPreHashed(passphrase)) return passphrase.lowercase()
        if (passphrase.length !in 8..63) return passphrase
        val spec = PBEKeySpec(passphrase.toCharArray(), ssid.toByteArray(Charsets.UTF_8), 4096, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec)
        return key.encoded.toHex()
    }

    fun isPreHashed(value: String): Boolean =
        value.length == 64 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}
