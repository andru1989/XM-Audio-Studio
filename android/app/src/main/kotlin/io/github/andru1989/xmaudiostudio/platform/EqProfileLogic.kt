package io.github.andru1989.xmaudiostudio.platform

import io.github.andru1989.xmaudiostudio.core.device.DeviceCapabilities
import io.github.andru1989.xmaudiostudio.core.device.DeviceCatalog
import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
import io.github.andru1989.xmaudiostudio.core.device.Feature
import io.github.andru1989.xmaudiostudio.core.device.ProtocolAdapter
import io.github.andru1989.xmaudiostudio.core.model.EqualizerPreset
import io.github.andru1989.xmaudiostudio.core.model.EqualizerState
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.presets.EqProfile
import io.github.andru1989.xmaudiostudio.core.presets.ProfileCategory
import io.github.andru1989.xmaudiostudio.core.session.DeviceTarget
import io.github.andru1989.xmaudiostudio.core.session.SessionSnapshot

/** One row of the equalizer widget. */
data class EqWidgetItem(val id: String, val name: String, val category: ProfileCategory)

/** Everything the equalizer widget renders; pure data so it can be compared and tested. */
data class EqWidgetStatus(
    /** Model, link and freshness for the status line; [NoiseControlStatus.availability] is the equalizer's. */
    val base: NoiseControlStatus,
    /** The selected model's profiles in the widget's order. */
    val profiles: List<EqWidgetItem>,
    /** Live: the profile the headphones report. Otherwise the last one seen live, if any. */
    val activeId: String?,
    /** Live EQ that matches no profile (edited by hand, or in another app). */
    val unsavedCurve: Boolean,
    /** Live listening mode (Background Music, Cinema) has the EQ switched off. */
    val blocked: Boolean,
)

/**
 * Pure decisions behind the equalizer widget: which profile is active, the order the widget
 * lists them in, what "next/previous" means and which intents applying one sends. Kept free of
 * Android types so it is covered by JVM tests.
 */
object EqProfileLogic {

    /** The user's own profiles first (quick access), then measured, subjective and Sony's presets. */
    private val categoryOrder = listOf(
        ProfileCategory.USER,
        ProfileCategory.MEASURED,
        ProfileCategory.SUBJECTIVE,
        ProfileCategory.DEVICE_PRESET,
    )

    fun ordered(profiles: List<EqProfile>): List<EqProfile> =
        categoryOrder.flatMap { category -> profiles.filter { it.category == category } }

    fun availability(target: DeviceTarget?, demoMode: Boolean, hasBluetoothPermission: Boolean): Availability = when {
        target == null -> Availability.NO_DEVICE
        ProtocolAdapter.forModel(target.model) == null || !supportsEq(DeviceCatalog.capabilities(target.model)) ->
            Availability.UNSUPPORTED_MODEL
        !demoMode && !hasBluetoothPermission -> Availability.PERMISSION_MISSING
        else -> Availability.READY
    }

    private fun supportsEq(caps: DeviceCapabilities) =
        caps.supports(Feature.EQ_PRESETS) || caps.supports(Feature.EQ_CUSTOM_BANDS)

    /**
     * The profile whose curve matches what the headphones report, preferring [lastAppliedId].
     * Same rules as the app: a custom curve matches by bands (and Clear Bass), a Sony preset by
     * code, and a Sound Connect user setting (unknown code) by its reported bands.
     */
    fun activeProfile(profiles: List<EqProfile>, eq: EqualizerState?, caps: DeviceCapabilities?, lastAppliedId: String?): EqProfile? {
        if (eq == null) return null
        val knownPreset = caps?.equalizer?.presets?.any { it.code == eq.presetCode } ?: false
        val candidates = if (eq.presetCode == EqualizerPreset.CUSTOM.code) {
            profiles.filter { it.deviceEq.steps == eq.bands && (it.deviceEq.clearBass ?: 0) == (eq.clearBass ?: 0) }
        } else {
            profiles.filter { it.deviceEq.presetCode == eq.presetCode || (!knownPreset && it.deviceEq.steps == eq.bands) }
        }
        return candidates.firstOrNull { it.id == lastAppliedId } ?: candidates.firstOrNull()
    }

    /**
     * The profile [direction] steps away (+1 next, -1 previous) from [currentId] in [ordered],
     * wrapping around. With no current profile, "next" starts at the first and "previous" at the last.
     */
    fun neighbour(ordered: List<EqProfile>, currentId: String?, direction: Int): EqProfile? {
        if (ordered.isEmpty()) return null
        val index = ordered.indexOfFirst { it.id == currentId }
        if (index < 0) return if (direction >= 0) ordered.first() else ordered.last()
        val size = ordered.size
        return ordered[((index + direction) % size + size) % size]
    }

    /**
     * The intents that put [profile]'s equalizer on the headphones. The widget applies only the
     * EQ: a scene's other settings need the confirmation the app shows before applying them.
     */
    fun commands(profile: EqProfile, caps: DeviceCapabilities): List<DeviceCommand> {
        val eq = profile.deviceEq
        eq.presetCode?.let { return listOf(DeviceCommand.SetEqualizerPreset(it)) }
        val steps = eq.steps ?: return emptyList()
        val clearBass = eq.clearBass ?: caps.equalizer?.clearBassRange?.let { 0 }
        return listOf(DeviceCommand.SetEqualizerBands(steps, clearBass))
    }

    /** The active profile read live from the headphones; null when not live or nothing matches. */
    fun liveActiveProfile(snapshot: SessionSnapshot, ordered: List<EqProfile>, lastAppliedId: String?): EqProfile? =
        if (snapshot.stateIsLive) activeProfile(ordered, snapshot.state.equalizer, snapshot.capabilities, lastAppliedId) else null

    /**
     * The widget's status. [profiles] are the selected model's; [lastKnown] supplies the active
     * profile only when the state is not live and the record belongs to the selected headphones.
     */
    fun status(
        base: NoiseControlStatus,
        snapshot: SessionSnapshot,
        profiles: List<EqProfile>,
        demoMode: Boolean,
        hasBluetoothPermission: Boolean,
        lastAppliedId: String?,
        lastKnown: LastKnownState?,
    ): EqWidgetStatus {
        val target = snapshot.target
        val ordered = ordered(profiles)
        val live = snapshot.stateIsLive
        val activeId = if (live) {
            liveActiveProfile(snapshot, ordered, lastAppliedId)?.id
        } else {
            lastKnown?.takeIf { target != null && it.address.equals(target.address, ignoreCase = true) }
                ?.eqProfileId?.takeIf { id -> ordered.any { it.id == id } }
        }
        return EqWidgetStatus(
            // Noise mode and battery are not shown: leaving them out avoids redrawing for them.
            base = base.copy(availability = availability(target, demoMode, hasBluetoothPermission), mode = null, batteryLevel = null, charging = false),
            profiles = ordered.map { EqWidgetItem(it.id, it.name, it.category) },
            activeId = activeId,
            unsavedCurve = live && snapshot.state.equalizer != null && activeId == null,
            blocked = live && listeningModeBlocksEq(snapshot.state.listeningMode),
        )
    }

    /** The firmware switches the EQ off while Background Music or Cinema is active. */
    fun listeningModeBlocksEq(mode: ListeningMode?): Boolean = mode != null && mode != ListeningMode.STANDARD
}
