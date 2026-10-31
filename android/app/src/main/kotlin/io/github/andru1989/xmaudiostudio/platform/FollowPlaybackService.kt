package io.github.andru1989.xmaudiostudio.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.andru1989.xmaudiostudio.MainActivity
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.XmAudioStudioApp
import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * "Seguir la reproducción" while the app is closed: when this phone starts playing to Bluetooth
 * headphones and keeps playing for 2 s, it asks the headphones for the audio (newest start wins,
 * docs/decisions/ADR-0011).
 *
 * It only listens to local audio events ([LocalPlayback]); it never holds the control channel. A
 * claim is an ordinary intent on the process-wide session: the channel opens, the real state is
 * read, [DeviceCommand.ClaimPlayback] writes nothing if this phone already has the audio, and the
 * session releases the channel 20 s later as with the widgets (docs/android/BACKGROUND.md).
 */
class FollowPlaybackService : Service() {
    private val container get() = (application as XmAudioStudioApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var localPlayback: LocalPlayback? = null
    private lateinit var claimer: PlaybackClaimer

    private val ticker = object : Runnable {
        override fun run() {
            if (claimer.tick()) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        claimer = PlaybackClaimer(
            now = SystemClock::elapsedRealtime,
            follow = { container.settings.followPlayback },
            clock = container.autoSwitch,
            claim = ::claim,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startInForeground() || !FollowPlayback.canRun(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (localPlayback == null) startListening()
        return START_STICKY
    }

    private fun startInForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        true
    } catch (e: SecurityException) {
        // API 34+: connectedDevice needs BLUETOOTH_CONNECT granted.
        Log.w(TAG, "Cannot run in the foreground: ${e.message}")
        false
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 31+).
        Log.w(TAG, "Cannot run in the foreground: ${e.message}")
        false
    }

    private fun startListening() {
        val playback = LocalPlayback(this, requireBluetoothOutput = !container.settings.demoMode)
        localPlayback = playback
        playback.start()
        scope.launch {
            // The first value is the state at start-up: music already playing is not a new start.
            playback.playingSince.drop(1).collect { since ->
                handler.removeCallbacks(ticker)
                if (claimer.onPlayingSince(since)) handler.postDelayed(ticker, TICK_MS)
            }
        }
    }

    private fun claim() {
        container.restoreSavedTarget()
        val name = if (container.settings.demoMode) DEMO_PHONE_NAME else LocalPlayback.bluetoothName(this)
        if (name == null) {
            Log.w(TAG, "This phone's Bluetooth name is unknown; cannot find it in the headphones' list")
            return
        }
        container.session.execute(DeviceCommand.ClaimPlayback(localName = name))
    }

    private fun notification() = run {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.follow_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                    description = getString(R.string.follow_channel_description)
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_follow_playback)
            .setContentTitle(getString(R.string.follow_notification_title))
            .setContentText(getString(R.string.follow_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        localPlayback?.stop()
        localPlayback = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FollowPlaybackService"
        private const val CHANNEL_ID = "follow_playback"
        private const val NOTIFICATION_ID = 41
        private const val TICK_MS = 1_000L
        /** The phone the XM6 simulator lists (SimulatedXm6Channel.devices). */
        private const val DEMO_PHONE_NAME = "Pixel"
    }
}
