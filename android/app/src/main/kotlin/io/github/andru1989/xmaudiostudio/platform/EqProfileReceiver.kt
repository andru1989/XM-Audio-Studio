package io.github.andru1989.xmaudiostudio.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.andru1989.xmaudiostudio.XmAudioStudioApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the equalizer widget's taps: a list row (apply that profile) and ◀ / ▶. Not exported:
 * only our own PendingIntents reach it. Same process budget as [NoiseControlReceiver].
 */
class EqProfileReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val request = when (intent.action) {
            ACTION_APPLY -> intent.getStringExtra(EXTRA_PROFILE_ID)?.let { EqProfileControl.Request.Apply(it) }
            ACTION_STEP -> EqProfileControl.Request.Step(if (intent.getIntExtra(EXTRA_DIRECTION, 1) < 0) -1 else 1)
            else -> null
        } ?: return
        val container = (context.applicationContext as XmAudioStudioApp).container
        val pending = goAsync()
        val work = container.appScope.launch { container.eqControl.perform(request) }
        container.appScope.launch {
            withTimeoutOrNull(RECEIVER_BUDGET_MS) { work.join() }
            pending.finish()
        }
    }

    companion object {
        const val ACTION_APPLY = "io.github.andru1989.xmaudiostudio.action.APPLY_EQ_PROFILE"
        const val ACTION_STEP = "io.github.andru1989.xmaudiostudio.action.STEP_EQ_PROFILE"
        const val EXTRA_PROFILE_ID = "profileId"
        const val EXTRA_DIRECTION = "direction"
        /** Below the 10 s a receiver may take; the work itself is not cancelled when it runs out. */
        private const val RECEIVER_BUDGET_MS = 9_000L

        fun stepIntent(context: Context, direction: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                if (direction < 0) 1 else 2,
                Intent(context, EqProfileReceiver::class.java).setAction(ACTION_STEP).putExtra(EXTRA_DIRECTION, direction),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /**
         * Template for the list rows; each row fills in [EXTRA_PROFILE_ID]. Mutable because the
         * launcher adds that extra, which is safe here: the intent is explicit and the receiver
         * is not exported.
         */
        fun applyTemplate(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                3,
                Intent(context, EqProfileReceiver::class.java).setAction(ACTION_APPLY),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
            )
    }
}
