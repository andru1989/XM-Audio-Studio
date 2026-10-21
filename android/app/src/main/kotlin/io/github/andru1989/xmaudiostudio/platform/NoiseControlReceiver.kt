package io.github.andru1989.xmaudiostudio.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.andru1989.xmaudiostudio.XmAudioStudioApp
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the widget's mode buttons. Not exported: only our own PendingIntents reach it.
 * Keeps the process alive with [goAsync] only until the intent is written (at most ~9 s); the
 * session keeps going afterwards and releases the channel on its own idle timer.
 */
class NoiseControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_MODE) return
        val mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { name -> AmbientSoundMode.entries.firstOrNull { it.name == name } }
            ?: return
        val container = (context.applicationContext as XmAudioStudioApp).container
        val pending = goAsync()
        val work = container.appScope.launch { container.noiseControl.perform(NoiseControl.Request.Set(mode)) }
        container.appScope.launch {
            withTimeoutOrNull(RECEIVER_BUDGET_MS) { work.join() }
            pending.finish()
        }
    }

    companion object {
        const val ACTION_SET_MODE = "io.github.andru1989.xmaudiostudio.action.SET_NOISE_MODE"
        const val EXTRA_MODE = "mode"
        /** Below the 10 s a receiver may take; the work itself is not cancelled when it runs out. */
        private const val RECEIVER_BUDGET_MS = 9_000L

        fun pendingIntent(context: Context, mode: AmbientSoundMode, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, NoiseControlReceiver::class.java).setAction(ACTION_SET_MODE).putExtra(EXTRA_MODE, mode.name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
