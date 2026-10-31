package io.github.andru1989.xmaudiostudio.platform

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.andru1989.xmaudiostudio.XmAudioStudioApp
import io.github.andru1989.xmaudiostudio.bluetooth.RfcommControlChannel
import io.github.andru1989.xmaudiostudio.core.device.PlaybackSourceAdvisor

/**
 * Last automatic playback-source action in this process (a claim by [FollowPlaybackService] or a
 * follow/recover by the open app), so the two never act within
 * [PlaybackSourceAdvisor.MIN_INTERVAL_MS] of each other. Lives in [io.github.andru1989.xmaudiostudio.AppContainer].
 */
class AutoSwitchClock {
    var lastMs: Long? = null
}

/**
 * "This phone started playing: take the audio" timing, without Android types so it can be unit
 * tested. [onPlayingSince] and [tick] return whether a 1 s ticker is still needed (playing and not
 * yet claimed); [claim] runs at most once per playback start (docs/decisions/ADR-0011).
 */
class PlaybackClaimer(
    private val now: () -> Long,
    private val follow: () -> Boolean,
    private val clock: AutoSwitchClock,
    private val claim: () -> Unit,
) {
    private var playingSinceMs: Long? = null
    private var claimedStartMs: Long? = null

    fun onPlayingSince(sinceMs: Long?): Boolean {
        playingSinceMs = sinceMs
        return tick()
    }

    fun tick(): Boolean {
        val since = playingSinceMs ?: return false
        if (!follow()) return false
        val t = now()
        if (PlaybackSourceAdvisor.shouldClaim(true, since, t, claimedStartMs, clock.lastMs)) {
            claimedStartMs = since
            clock.lastMs = t
            claim()
        }
        // Keep ticking only while this start is still waiting for its claim.
        return claimedStartMs != since
    }
}

/** Starts or stops [FollowPlaybackService] to match the "follow playback" setting. */
object FollowPlayback {
    private const val TAG = "FollowPlayback"

    /**
     * Following needs saved headphones (the simulated XM6 in demo mode) and the Bluetooth permission,
     * which Android 14+ also requires for a connectedDevice foreground service, even in demo mode.
     */
    fun canRun(context: Context): Boolean {
        val container = (context.applicationContext as XmAudioStudioApp).container
        if (!container.settings.followPlayback || container.savedTarget() == null) return false
        return RfcommControlChannel.hasConnectPermission(context)
    }

    fun sync(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, FollowPlaybackService::class.java)
        try {
            if (canRun(app)) {
                ContextCompat.startForegroundService(app, intent)
            } else {
                app.stopService(intent)
            }
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+): not allowed from the background now.
            Log.w(TAG, "Follow playback service not started: ${e.message}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Follow playback service not started: ${e.message}")
        }
    }
}
