package io.github.andru1989.xmaudiostudio.platform

import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import io.github.andru1989.xmaudiostudio.bluetooth.RfcommControlChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this phone is sending music to Bluetooth headphones right now, event-driven through
 * [AudioManager.registerAudioPlaybackCallback] (API 26). No permission is needed: only the
 * "music is active" flag and the output device types are read, never what is playing or by whom.
 *
 * @param requireBluetoothOutput false in demo mode, where there is no real Bluetooth audio.
 */
class LocalPlayback(context: Context, private val requireBluetoothOutput: Boolean) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _playingSince = MutableStateFlow<Long?>(null)

    /** [SystemClock.elapsedRealtime] when local playback started; null while nothing plays. */
    val playingSince: StateFlow<Long?> = _playingSince.asStateFlow()

    private var started = false
    private val recheck = Runnable { update() }

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            update()
            // The "music active" flag can trail the configuration change by a moment.
            handler.removeCallbacks(recheck)
            handler.postDelayed(recheck, RECHECK_MS)
        }
    }

    fun start() {
        if (started || audio == null) return
        started = true
        audio.registerAudioPlaybackCallback(callback, handler)
        update()
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(recheck)
        audio?.unregisterAudioPlaybackCallback(callback)
    }

    /** Re-reads the flags, e.g. when the app comes back to the foreground. */
    fun update() {
        val playing = audio != null && audio.isMusicActive && (!requireBluetoothOutput || hasBluetoothOutput(audio))
        _playingSince.value = when {
            !playing -> null
            _playingSince.value != null -> _playingSince.value
            else -> SystemClock.elapsedRealtime()
        }
    }

    private fun hasBluetoothOutput(audio: AudioManager): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

    companion object {
        private const val RECHECK_MS = 500L

        /**
         * This phone's Bluetooth name, as the headphones list it (Android hides its own address).
         * Null when it cannot be read (missing BLUETOOTH_CONNECT and no device name setting).
         */
        fun bluetoothName(context: Context): String? {
            val adapterName = try {
                if (RfcommControlChannel.hasConnectPermission(context)) context.getSystemService(BluetoothManager::class.java)?.adapter?.name else null
            } catch (_: SecurityException) {
                null
            }
            return adapterName?.takeIf { it.isNotBlank() }
                ?: Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        }
    }
}
