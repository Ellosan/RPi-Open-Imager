package dev.openimager.settings

import android.content.Context
import android.util.Log
import dev.openimager.core.customization.CustomizationSettings
import kotlinx.serialization.json.Json

/**
 * Remembers the customisation sheet between runs, the way the desktop tool does. Only the derived
 * secrets are stored - a `$6$` crypt hash and a WPA PSK - never the passwords themselves.
 */
class SettingsStore(context: Context) {

    private val preferences = context.getSharedPreferences("open-imager", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var customization: CustomizationSettings
        get() = preferences.getString(KEY_CUSTOMIZATION, null)?.let { stored ->
            runCatching { json.decodeFromString(CustomizationSettings.serializer(), stored) }
                .onFailure { Log.w(TAG, "discarding unreadable customisation settings", it) }
                .getOrNull()
        } ?: CustomizationSettings()
        set(value) {
            preferences.edit()
                .putString(KEY_CUSTOMIZATION, json.encodeToString(CustomizationSettings.serializer(), value))
                .apply()
        }

    var verifyAfterWrite: Boolean
        get() = preferences.getBoolean(KEY_VERIFY, true)
        set(value) = preferences.edit().putBoolean(KEY_VERIFY, value).apply()

    /** Writing to `/dev/block` nodes as root is powerful and easy to point at the wrong device. */
    var rootAccessEnabled: Boolean
        get() = preferences.getBoolean(KEY_ROOT, false)
        set(value) = preferences.edit().putBoolean(KEY_ROOT, value).apply()

    var selectedHardware: String?
        get() = preferences.getString(KEY_HARDWARE, null)
        set(value) = preferences.edit().putString(KEY_HARDWARE, value).apply()

    private companion object {
        const val TAG = "SettingsStore"
        const val KEY_CUSTOMIZATION = "customization"
        const val KEY_VERIFY = "verify"
        const val KEY_ROOT = "root_access"
        const val KEY_HARDWARE = "hardware"
    }
}
