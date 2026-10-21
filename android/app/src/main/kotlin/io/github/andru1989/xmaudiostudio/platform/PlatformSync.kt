package io.github.andru1989.xmaudiostudio.platform

import android.content.Context
import androidx.core.content.edit
import io.github.andru1989.xmaudiostudio.core.session.HeadphoneSession
import io.github.andru1989.xmaudiostudio.core.session.SessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** SharedPreferences home of [LastKnownState]; survives process death (and is backed up with the rest). */
class LastKnownStore(context: Context) {
    private val prefs = context.getSharedPreferences("platform", Context.MODE_PRIVATE)
    private var cached: LastKnownState? = null
    private var loaded = false

    fun load(): LastKnownState? {
        if (!loaded) {
            cached = LastKnownState.decode(prefs.getString(KEY, null))
            loaded = true
        }
        return cached
    }

    fun save(state: LastKnownState) {
        cached = state
        loaded = true
        prefs.edit { putString(KEY, state.encode()) }
    }

    private companion object {
        const val KEY = "lastKnownState"
    }
}

/**
 * Keeps the platform surfaces in step with the process-wide session while the process is alive:
 * every snapshot is reduced to a [NoiseControlStatus]; distinct statuses are pushed to the
 * home-screen widgets and exposed to the Quick Settings tile, and live state is persisted so a
 * later process can still show "último estado conocido".
 */
class PlatformSync(
    private val context: Context,
    private val sessions: StateFlow<HeadphoneSession>,
    private val store: LastKnownStore,
    private val demoMode: () -> Boolean,
    private val hasBluetoothPermission: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val tracker = LastKnownTracker()
    private val _status = MutableStateFlow(statusOf(sessions.value.snapshot.value))
    val status: StateFlow<NoiseControlStatus> = _status.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        NoiseWidgetProvider.updateAll(context, _status.value)
        scope.launch {
            sessions.flatMapLatest { it.snapshot }.collect { snapshot ->
                tracker.onSnapshot(snapshot, System.currentTimeMillis())?.let(store::save)
                publish(statusOf(snapshot))
            }
        }
    }

    /** Re-evaluates inputs the snapshot does not carry (permission granted, demo toggled). */
    fun refresh(): NoiseControlStatus {
        val status = statusOf(sessions.value.snapshot.value)
        publish(status)
        return status
    }

    private fun publish(status: NoiseControlStatus) {
        if (status == _status.value) return
        _status.value = status
        NoiseWidgetProvider.updateAll(context, status)
    }

    private fun statusOf(snapshot: SessionSnapshot) =
        NoiseControlLogic.status(snapshot, demoMode(), hasBluetoothPermission(), store.load())
}
