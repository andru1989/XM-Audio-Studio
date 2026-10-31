package io.github.andru1989.xmaudiostudio.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts [FollowPlaybackService] after a reboot or an app update when "Seguir la reproducción"
 * is on (both broadcasts may start a connectedDevice foreground service from the background).
 */
class FollowPlaybackBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            FollowPlayback.sync(context)
        }
    }
}
