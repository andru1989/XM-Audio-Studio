package io.github.andru1989.xmaudiostudio.platform

import android.content.Context
import androidx.core.content.edit
import io.github.andru1989.xmaudiostudio.core.presets.EqProfile
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
 * every snapshot is reduced to a [NoiseControlStatus] and an [EqWidgetStatus]; distinct statuses
 * are pushed to the home-screen widgets and exposed to the Quick Settings tile, and live state is
 * persisted so a later process can still show "último estado conocido".
 */
class PlatformSync(
    private val context: Context,
    private val sessions: StateFlow<HeadphoneSession>,
    private val store: LastKnownStore,
    private val demoMode: () -> Boolean,
    private val hasBluetoothPermission: () -> Boolean,
    /** The profiles of a model (built-in + user), for the equalizer widget. */
    private val profiles: (modelId: String) -> List<EqProfile>,
    private val lastAppliedProfileId: () -> String?,
    private val scope: CoroutineScope,
) {
    private val tracker = LastKnownTracker()
    private val _status = MutableStateFlow(statusOf(sessions.value.snapshot.value))
    val status: StateFlow<NoiseControlStatus> = _status.asStateFlow()
    private val _eqStatus = MutableStateFlow(eqStatusOf(sessions.value.snapshot.value, _status.value))
    val eqStatus: StateFlow<EqWidgetStatus> = _eqStatus.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        NoiseWidgetProvider.updateAll(context, _status.value)
        EqWidgetProvider.updateAll(context, _eqStatus.value)
        scope.launch {
            sessions.flatMapLatest { it.snapshot }.collect { snapshot ->
                tracker.onSnapshot(snapshot, System.currentTimeMillis(), liveEqProfileId(snapshot))?.let(store::save)
                publish(snapshot)
            }
        }
    }

    /**
     * Re-evaluates inputs the snapshot does not carry (permission granted, demo toggled, profiles
     * saved, renamed or deleted in the app).
     */
    fun refresh(): NoiseControlStatus {
        publish(sessions.value.snapshot.value)
        return _status.value
    }

    private fun publish(snapshot: SessionSnapshot) {
        val status = statusOf(snapshot)
        if (status != _status.value) {
            _status.value = status
            NoiseWidgetProvider.updateAll(context, status)
        }
        val eq = eqStatusOf(snapshot, status)
        if (eq != _eqStatus.value) {
            _eqStatus.value = eq
            EqWidgetProvider.updateAll(context, eq)
        }
    }

    private fun statusOf(snapshot: SessionSnapshot) =
        NoiseControlLogic.status(snapshot, demoMode(), hasBluetoothPermission(), store.load())

    private fun modelProfiles(snapshot: SessionSnapshot): List<EqProfile> =
        snapshot.target?.let { profiles(it.model.id) }.orEmpty()

    private fun eqStatusOf(snapshot: SessionSnapshot, base: NoiseControlStatus) = EqProfileLogic.status(
        base, snapshot, modelProfiles(snapshot), demoMode(), hasBluetoothPermission(), lastAppliedProfileId(), store.load(),
    )

    private fun liveEqProfileId(snapshot: SessionSnapshot): String? =
        EqProfileLogic.liveActiveProfile(snapshot, EqProfileLogic.ordered(modelProfiles(snapshot)), lastAppliedProfileId())?.id
}
