package io.github.andru1989.xmaudiostudio.data

import android.content.Context
import androidx.core.content.edit

/** App preferences (not headphone settings). Nothing here leaves the device. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var selectedAddress: String?
        get() = prefs.getString("selectedAddress", null)
        set(value) = prefs.edit { putString("selectedAddress", value) }

    var selectedName: String?
        get() = prefs.getString("selectedName", null)
        set(value) = prefs.edit { putString("selectedName", value) }

    /** Model chosen by the user when the Bluetooth name does not identify it. */
    var modelOverride: String?
        get() = prefs.getString("modelOverride", null)
        set(value) = prefs.edit { putString("modelOverride", value) }

    var demoMode: Boolean
        get() = prefs.getBoolean("demoMode", false)
        set(value) = prefs.edit { putBoolean("demoMode", value) }

    var releaseWhenIdle: Boolean
        get() = prefs.getBoolean("releaseWhenIdle", true)
        set(value) = prefs.edit { putBoolean("releaseWhenIdle", value) }

    var protocolLogging: Boolean
        get() = prefs.getBoolean("protocolLogging", false)
        set(value) = prefs.edit { putBoolean("protocolLogging", value) }

    /** The profile last sent to the headphones from this app (used to label the active curve). */
    var lastAppliedProfileId: String?
        get() = prefs.getString("lastAppliedProfileId", null)
        set(value) = prefs.edit { putString("lastAppliedProfileId", value) }
}
