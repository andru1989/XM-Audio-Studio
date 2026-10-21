package io.github.andru1989.xmaudiostudio.platform

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import java.text.DateFormat

/** Localised text and icons for [NoiseControlStatus], shared by the tile and the widget. */
internal object PlatformText {

    fun modeName(context: Context, mode: AmbientSoundMode?): String = context.getString(
        when (mode) {
            AmbientSoundMode.NOISE_CANCELLING -> R.string.noise_mode_nc
            AmbientSoundMode.AMBIENT_SOUND -> R.string.noise_mode_ambient
            AmbientSoundMode.OFF -> R.string.noise_mode_off
            null -> R.string.noise_mode_unknown
        },
    )

    @DrawableRes
    fun modeIcon(mode: AmbientSoundMode?): Int = when (mode) {
        AmbientSoundMode.AMBIENT_SOUND -> R.drawable.ic_noise_ambient
        AmbientSoundMode.OFF -> R.drawable.ic_noise_off
        AmbientSoundMode.NOISE_CANCELLING, null -> R.drawable.ic_noise_cancelling
    }

    fun model(context: Context, status: NoiseControlStatus): String? {
        val name = status.modelName ?: return null
        return if (status.isDemo) context.getString(R.string.platform_model_demo, name) else name
    }

    fun battery(context: Context, status: NoiseControlStatus): String? {
        val level = status.batteryLevel ?: return null
        return context.getString(if (status.charging) R.string.platform_battery_charging else R.string.platform_battery, level)
    }

    /** Widget status line: why it cannot act, or how fresh the values are. */
    fun widgetStatus(context: Context, status: NoiseControlStatus): String = when (status.availability) {
        Availability.NO_DEVICE -> context.getString(R.string.platform_no_device)
        Availability.UNSUPPORTED_MODEL -> context.getString(R.string.platform_unsupported)
        Availability.PERMISSION_MISSING -> context.getString(R.string.platform_permission)
        Availability.READY -> when (status.link) {
            LinkActivity.CONNECTING -> context.getString(R.string.platform_status_connecting)
            LinkActivity.FAILED -> context.getString(R.string.platform_status_failed)
            LinkActivity.YIELDED -> context.getString(R.string.platform_status_yielded)
            LinkActivity.CONNECTED, LinkActivity.IDLE -> when (status.freshness) {
                Freshness.LIVE -> context.getString(R.string.platform_status_live)
                Freshness.LAST_KNOWN -> context.getString(R.string.platform_status_last_known, time(status.lastKnownAtMs))
                Freshness.UNKNOWN -> context.getString(R.string.platform_status_unknown)
            }
        }
    }

    /** Tile subtitle (Android 10+); short by design. */
    fun tileSubtitle(context: Context, status: NoiseControlStatus): String = when (status.availability) {
        Availability.NO_DEVICE -> context.getString(R.string.tile_sub_no_device)
        Availability.UNSUPPORTED_MODEL -> context.getString(R.string.tile_sub_unsupported)
        Availability.PERMISSION_MISSING -> context.getString(R.string.tile_sub_permission)
        Availability.READY -> when (status.link) {
            LinkActivity.CONNECTING -> context.getString(R.string.platform_status_connecting)
            LinkActivity.FAILED -> context.getString(R.string.tile_sub_failed)
            LinkActivity.YIELDED -> context.getString(R.string.tile_sub_yielded)
            LinkActivity.CONNECTED, LinkActivity.IDLE -> when (status.freshness) {
                Freshness.LIVE -> listOfNotNull(model(context, status), battery(context, status)).joinToString(" · ")
                Freshness.LAST_KNOWN -> context.getString(R.string.tile_sub_last_known)
                Freshness.UNKNOWN -> context.getString(R.string.tile_sub_tap)
            }
        }
    }

    /**
     * Absolute time (date too when not today): the widget may keep showing this line long after
     * the process is gone, so a relative "5 min ago" would go stale.
     */
    private fun time(epochMs: Long?): String =
        if (epochMs == null) "—" else DateUtils.formatSameDayTime(epochMs, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT).toString()
}
