package io.github.andru1989.xmaudiostudio.platform

import io.github.andru1989.xmaudiostudio.core.device.DeviceCatalog
import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
import io.github.andru1989.xmaudiostudio.core.device.Feature
import io.github.andru1989.xmaudiostudio.core.device.ProtocolAdapter
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundState
import io.github.andru1989.xmaudiostudio.core.session.ConnectionPhase
import io.github.andru1989.xmaudiostudio.core.session.DeviceTarget
import io.github.andru1989.xmaudiostudio.core.session.SessionSnapshot

/** Whether the Quick Settings tile and the widget may act right now. */
enum class Availability {
    READY,
    /** No headphones selected in the app (and demo mode off). */
    NO_DEVICE,
    /** Selected model has no protocol adapter yet: nothing may be sent to it. */
    UNSUPPORTED_MODEL,
    /** Real hardware needs BLUETOOTH_CONNECT (Android 12+), which only the app can request. */
    PERMISSION_MISSING,
}

/** Where the values a tile/widget shows come from. */
enum class Freshness {
    /** Read from the headphones during the current connection. */
    LIVE,
    /** Persisted from an earlier connection (possibly by a process that no longer exists). */
    LAST_KNOWN,
    UNKNOWN,
}

/** The control channel reduced to what a tile/widget can show. */
enum class LinkActivity { IDLE, CONNECTING, CONNECTED, YIELDED, FAILED }

/** Everything the tile and the widget render; pure data so it can be compared and tested. */
data class NoiseControlStatus(
    val availability: Availability,
    val modelName: String?,
    val isDemo: Boolean,
    val mode: AmbientSoundMode?,
    val batteryLevel: Int?,
    val charging: Boolean,
    val freshness: Freshness,
    val link: LinkActivity,
    /** When [freshness] is [Freshness.LAST_KNOWN]: when that state was last confirmed (epoch ms). */
    val lastKnownAtMs: Long?,
)

/** Pure rules shared by the tile, the widget and their tests (no Android types). */
object NoiseControlLogic {

    /** Tile cycle, same order as the headphones' NC/AMB button: NC → Ambient → Off → NC. */
    fun nextMode(current: AmbientSoundMode): AmbientSoundMode = when (current) {
        AmbientSoundMode.NOISE_CANCELLING -> AmbientSoundMode.AMBIENT_SOUND
        AmbientSoundMode.AMBIENT_SOUND -> AmbientSoundMode.OFF
        AmbientSoundMode.OFF -> AmbientSoundMode.NOISE_CANCELLING
    }

    /**
     * The intent for [mode], keeping the companion values (focus on voice, ambient level) the
     * headphones reported. Writes use level 1..20, as in the main screen.
     */
    fun command(mode: AmbientSoundMode, current: AmbientSoundState): DeviceCommand.SetAmbientSound =
        DeviceCommand.SetAmbientSound(mode, current.focusOnVoice, current.level.coerceAtLeast(1))

    fun availability(target: DeviceTarget?, demoMode: Boolean, hasBluetoothPermission: Boolean): Availability = when {
        target == null -> Availability.NO_DEVICE
        ProtocolAdapter.forModel(target.model) == null ||
            !DeviceCatalog.capabilities(target.model).supports(Feature.NOISE_CANCELLING) -> Availability.UNSUPPORTED_MODEL
        !demoMode && !hasBluetoothPermission -> Availability.PERMISSION_MISSING
        else -> Availability.READY
    }

    fun link(phase: ConnectionPhase): LinkActivity = when (phase) {
        ConnectionPhase.IDLE -> LinkActivity.IDLE
        ConnectionPhase.CONNECTING, ConnectionPhase.INITIALIZING, ConnectionPhase.READING_STATE -> LinkActivity.CONNECTING
        ConnectionPhase.CONNECTED -> LinkActivity.CONNECTED
        ConnectionPhase.YIELDED -> LinkActivity.YIELDED
        ConnectionPhase.FAILED -> LinkActivity.FAILED
    }

    /**
     * Live values when the session has them; otherwise the persisted [lastKnown] state, but only
     * if it belongs to the selected headphones (a demo record never labels real hardware).
     */
    fun status(
        snapshot: SessionSnapshot,
        demoMode: Boolean,
        hasBluetoothPermission: Boolean,
        lastKnown: LastKnownState?,
    ): NoiseControlStatus {
        val target = snapshot.target
        val known = lastKnown?.takeIf { target != null && it.address.equals(target.address, ignoreCase = true) }
        val live = snapshot.stateIsLive
        val freshness = when {
            live -> Freshness.LIVE
            known != null -> Freshness.LAST_KNOWN
            else -> Freshness.UNKNOWN
        }
        return NoiseControlStatus(
            availability = availability(target, demoMode, hasBluetoothPermission),
            modelName = target?.model?.displayName,
            isDemo = demoMode && target != null,
            mode = if (live) snapshot.state.ambientSound?.mode else known?.mode,
            batteryLevel = if (live) snapshot.state.battery?.level else known?.batteryLevel,
            charging = if (live) snapshot.state.battery?.isCharging == true else known?.charging == true,
            freshness = freshness,
            link = link(snapshot.phase),
            lastKnownAtMs = if (freshness == Freshness.LAST_KNOWN) known?.timestampMs else null,
        )
    }
}
