package io.github.andru1989.xmaudiostudio.platform

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.XmAudioStudioApp
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: each tap cycles Cancelación de ruido → Ambiente → Desactivado.
 *
 * Not an "active" tile: Android binds it while the Quick Settings panel is open, and during that
 * time it follows [PlatformSync.status]. A tap goes through [NoiseControl], which reads the real
 * state (connecting on demand) before choosing the next mode. No foreground service: the session's
 * 20 s idle release applies as for any other use (docs/android/BACKGROUND.md).
 */
class NoiseControlTileService : TileService() {

    private val container get() = (application as XmAudioStudioApp).container
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        container.restoreSavedTarget()
        container.platform.refresh()
        listening?.cancel()
        listening = container.appScope.launch {
            container.platform.status.collect(::render)
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val c = container
        c.appScope.launch { c.noiseControl.perform(NoiseControl.Request.Cycle) }
    }

    private fun render(status: NoiseControlStatus) {
        val tile = qsTile ?: return
        val ready = status.availability == Availability.READY
        val mode = status.mode.takeIf { ready }
        tile.state = when {
            !ready -> Tile.STATE_UNAVAILABLE
            mode == AmbientSoundMode.NOISE_CANCELLING || mode == AmbientSoundMode.AMBIENT_SOUND -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = if (mode != null) PlatformText.modeName(this, mode) else getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, PlatformText.modeIcon(mode))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = PlatformText.tileSubtitle(this, status)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = listOfNotNull(
                getString(R.string.tile_label),
                mode?.let { PlatformText.modeName(this, it) },
                PlatformText.tileSubtitle(this, status),
            ).joinToString(", ")
        }
        tile.updateTile()
    }
}
