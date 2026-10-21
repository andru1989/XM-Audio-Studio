package io.github.andru1989.xmaudiostudio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.andru1989.xmaudiostudio.XmAudioStudioApp
import io.github.andru1989.xmaudiostudio.bluetooth.PairedDevice
import io.github.andru1989.xmaudiostudio.bluetooth.PairedDevices
import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.eq.FitResult
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.EqualizerPreset
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig
import io.github.andru1989.xmaudiostudio.core.presets.AutoEqImporter
import io.github.andru1989.xmaudiostudio.core.presets.DeviceEqSetting
import io.github.andru1989.xmaudiostudio.core.presets.EqProfile
import io.github.andru1989.xmaudiostudio.core.presets.ProfileCategory
import io.github.andru1989.xmaudiostudio.core.presets.ProfileFormatException
import io.github.andru1989.xmaudiostudio.core.presets.ProfileSource
import io.github.andru1989.xmaudiostudio.core.presets.SourceCurve
import io.github.andru1989.xmaudiostudio.core.session.DeviceTarget
import io.github.andru1989.xmaudiostudio.core.session.HeadphoneSession
import io.github.andru1989.xmaudiostudio.core.session.SessionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A preview of an AutoEQ import before anything is saved or sent. */
data class AutoEqPreview(val name: String, val fit: FitResult, val sourceCurve: SourceCurve?)

data class UiState(
    val snapshot: SessionSnapshot = SessionSnapshot(),
    val devices: PairedDevices.Result? = null,
    val demoMode: Boolean = false,
    val releaseWhenIdle: Boolean = true,
    val protocolLogging: Boolean = false,
    val profiles: List<EqProfile> = emptyList(),
    /** Profile whose curve matches what the headphones report, if any. */
    val activeProfile: EqProfile? = null,
    val canUndo: Boolean = false,
    /** The other side of the A/B comparison, when one exists. */
    val compareWith: DeviceEqSetting? = null,
    val autoEqPreview: AutoEqPreview? = null,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as XmAudioStudioApp).container
    private var session: HeadphoneSession = container.session
    private val settings = container.settings
    private val library = container.profiles

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val history = ArrayDeque<DeviceEqSetting>()
    private var compareWith: DeviceEqSetting? = null
    private var dragOrigin: DeviceEqSetting? = null
    private var snapshotJob: Job? = null
    private var surfaceVisible = false

    init {
        container.protocolLog.enabled = settings.protocolLogging
        _ui.update {
            it.copy(demoMode = settings.demoMode, releaseWhenIdle = settings.releaseWhenIdle, protocolLogging = settings.protocolLogging)
        }
        observeSession()
        restoreTarget()
        refreshProfiles()
    }

    private fun observeSession() {
        snapshotJob?.cancel()
        snapshotJob = viewModelScope.launch {
            session.snapshot.collect { snap -> _ui.update { it.copy(snapshot = snap, activeProfile = matchActiveProfile(snap)) } }
        }
    }

    // region Device selection

    fun refreshDevices() {
        _ui.update { it.copy(devices = PairedDevices.list(getApplication())) }
    }

    private fun restoreTarget() {
        if (settings.demoMode) {
            session.selectTarget(DeviceTarget(DEMO_ADDRESS, "WH-1000XM6 (demo)", HeadphoneModel.WH_1000XM6))
            return
        }
        val address = settings.selectedAddress ?: return
        val name = settings.selectedName ?: address
        val model = settings.modelOverride?.let(HeadphoneModel::fromId) ?: HeadphoneModel.fromBluetoothName(name) ?: return
        session.selectTarget(DeviceTarget(address, name, model))
    }

    /** [modelOverride] is set when the user confirms the model of a renamed device. */
    fun selectDevice(device: PairedDevice, modelOverride: HeadphoneModel? = null) {
        val model = modelOverride ?: device.model ?: return
        settings.selectedAddress = device.address
        settings.selectedName = device.name
        settings.modelOverride = modelOverride?.id
        history.clear()
        compareWith = null
        session.selectTarget(DeviceTarget(device.address, device.name, model))
        refreshProfiles()
    }

    /** A rebuilt session starts with no surfaces; re-announce this screen so it acquires as usual. */
    private fun rebuildSession() {
        session = container.rebuildSession()
        observeSession()
        restoreTarget()
        if (surfaceVisible) session.setSurfaceVisible(SURFACE_ID, true)
        history.clear()
        compareWith = null
        _ui.update { it.copy(canUndo = false, compareWith = null) }
    }

    fun setDemoMode(enabled: Boolean) {
        settings.demoMode = enabled
        rebuildSession()
        refreshProfiles()
        _ui.update { it.copy(demoMode = enabled) }
    }

    fun setReleaseWhenIdle(enabled: Boolean) {
        settings.releaseWhenIdle = enabled
        rebuildSession()
        _ui.update { it.copy(releaseWhenIdle = enabled) }
    }

    fun setProtocolLogging(enabled: Boolean) {
        settings.protocolLogging = enabled
        container.protocolLog.enabled = enabled
        _ui.update { it.copy(protocolLogging = enabled) }
    }

    fun protocolLogText(): String = container.protocolLog.read()

    fun setSurfaceVisible(visible: Boolean) {
        surfaceVisible = visible
        session.setSurfaceVisible(SURFACE_ID, visible)
    }
    fun connect() = session.connect()
    fun disconnect() = session.disconnect()
    fun refresh() = session.refresh()

    /** Demo only: emulate pressing the NC/AMB button on the headphones. */
    fun simulateButtonPress() = container.simulator?.simulateNoiseControlButton()

    /** Demo only: emulate a phone taking over the control channel. */
    fun simulateTakeover() = container.simulator?.simulateRemoteTakeover()

    // endregion
    // region Headphone controls

    private fun ambient() = _ui.value.snapshot.state.ambientSound

    /** MDR v1 headsets report level 0 while noise cancelling is on; fall back to a usable level. */
    private fun ambientLevelToWrite() = ambient()?.level?.takeIf { it >= 1 } ?: 15

    fun setNoiseMode(mode: AmbientSoundMode) {
        val a = ambient()
        session.execute(DeviceCommand.SetAmbientSound(mode, a?.focusOnVoice ?: false, ambientLevelToWrite()))
    }

    fun setAmbientLevel(level: Int) {
        val a = ambient()
        session.execute(DeviceCommand.SetAmbientSound(AmbientSoundMode.AMBIENT_SOUND, a?.focusOnVoice ?: false, level))
    }

    fun setFocusOnVoice(enabled: Boolean) {
        session.execute(DeviceCommand.SetAmbientSound(AmbientSoundMode.AMBIENT_SOUND, enabled, ambientLevelToWrite()))
    }

    fun setListeningMode(mode: ListeningMode) = session.execute(DeviceCommand.SetListeningMode(mode))
    fun setRoomSize(size: BgmRoomSize) = session.execute(DeviceCommand.SetBgmRoomSize(size))
    fun setSpeakToChat(enabled: Boolean) = session.execute(DeviceCommand.SetSpeakToChat(enabled))
    fun setSpeakToChatConfig(config: SpeakToChatConfig) = session.execute(DeviceCommand.SetSpeakToChatConfig(config))
    fun setAutoPowerOff(mode: AutomaticPowerOffMode) = session.execute(DeviceCommand.SetAutomaticPowerOff(mode))
    fun setPauseWhenTakenOff(enabled: Boolean) = session.execute(DeviceCommand.SetPauseWhenTakenOff(enabled))
    fun switchPlayback(mac: String) = session.execute(DeviceCommand.SwitchPlayback(mac))

    // endregion
    // region Equalizer

    private fun currentSetting(): DeviceEqSetting? {
        val eq = _ui.value.snapshot.state.equalizer ?: return null
        return if (eq.presetCode == EqualizerPreset.CUSTOM.code) {
            DeviceEqSetting(steps = eq.bands, clearBass = eq.clearBass)
        } else {
            DeviceEqSetting(presetCode = eq.presetCode)
        }
    }

    /** 0 on models with a Clear Bass control (a profile defines the whole curve), null otherwise. */
    private fun neutralClearBass(): Int? = _ui.value.snapshot.capabilities?.equalizer?.clearBassRange?.let { 0 }

    private fun remember(previous: DeviceEqSetting?) {
        if (previous == null) return
        if (history.lastOrNull() != previous) history.addLast(previous)
        while (history.size > MAX_HISTORY) history.removeFirst()
        compareWith = previous
        _ui.update { it.copy(canUndo = history.isNotEmpty(), compareWith = compareWith) }
    }

    private fun send(setting: DeviceEqSetting) {
        setting.presetCode?.let { session.execute(DeviceCommand.SetEqualizerPreset(it)) }
        setting.steps?.let { session.execute(DeviceCommand.SetEqualizerBands(it, clearBass = setting.clearBass ?: neutralClearBass())) }
    }

    fun applyProfile(profile: EqProfile) {
        remember(currentSetting())
        settings.lastAppliedProfileId = profile.id
        send(profile.deviceEq)
    }

    /** Live band edit while dragging; the session coalesces writes (one in flight at a time). */
    fun setBand(index: Int, step: Int) {
        val current = currentSetting() ?: return
        if (dragOrigin == null) dragOrigin = current
        val bands = _ui.value.snapshot.state.equalizer?.bands?.takeIf { it.isNotEmpty() }
            ?: List(_ui.value.snapshot.capabilities?.equalizer?.bandCount ?: return) { 0 }
        val updated = bands.toMutableList().also { it[index] = step }
        settings.lastAppliedProfileId = null
        // Clear Bass (5-band models) is left as the headphones report it.
        session.execute(DeviceCommand.SetEqualizerBands(updated))
    }

    /** Live Clear Bass edit (XM3-XM5); the bands are re-sent unchanged in the same message. */
    fun setClearBass(step: Int) {
        val current = currentSetting() ?: return
        if (dragOrigin == null) dragOrigin = current
        val count = _ui.value.snapshot.capabilities?.equalizer?.bandCount ?: return
        val bands = _ui.value.snapshot.state.equalizer?.bands?.takeIf { it.size == count } ?: List(count) { 0 }
        settings.lastAppliedProfileId = null
        session.execute(DeviceCommand.SetEqualizerBands(bands, clearBass = step))
    }

    fun finishBandEdit() {
        remember(dragOrigin)
        dragOrigin = null
    }

    fun resetFlat() {
        val count = _ui.value.snapshot.capabilities?.equalizer?.bandCount ?: return
        remember(currentSetting())
        settings.lastAppliedProfileId = "builtin.flat"
        session.execute(DeviceCommand.SetEqualizerBands(List(count) { 0 }, clearBass = neutralClearBass()))
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        compareWith = currentSetting()
        settings.lastAppliedProfileId = null
        send(previous)
        _ui.update { it.copy(canUndo = history.isNotEmpty(), compareWith = compareWith) }
    }

    /** A/B: swaps the current curve with the previous one. */
    fun toggleCompare() {
        val other = compareWith ?: return
        compareWith = currentSetting()
        settings.lastAppliedProfileId = null
        send(other)
        _ui.update { it.copy(compareWith = compareWith) }
    }

    // endregion
    // region Profiles

    private fun modelId() = _ui.value.snapshot.target?.model?.id ?: HeadphoneModel.WH_1000XM6.id

    private fun refreshProfiles() {
        _ui.update { it.copy(profiles = library.forModel(modelId()), activeProfile = matchActiveProfile(it.snapshot)) }
    }

    private fun matchActiveProfile(snap: SessionSnapshot): EqProfile? {
        val eq = snap.state.equalizer ?: return null
        val all = library.forModel(snap.target?.model?.id ?: return null)
        val userPreset = isUserPresetFromOfficialApp(snap)
        val candidates = if (eq.presetCode == EqualizerPreset.CUSTOM.code) {
            all.filter { it.deviceEq.steps == eq.bands && (it.deviceEq.clearBass ?: 0) == (eq.clearBass ?: 0) }
        } else {
            // A Sound Connect user setting matches a saved profile with the same bands.
            all.filter { it.deviceEq.presetCode == eq.presetCode || (userPreset && it.deviceEq.steps == eq.bands) }
        }
        return candidates.firstOrNull { it.id == settings.lastAppliedProfileId } ?: candidates.firstOrNull()
    }

    /** A preset code this app does not know, e.g. a user setting (0xA1+) made in Sound Connect. */
    fun isUserPresetFromOfficialApp(snap: SessionSnapshot = _ui.value.snapshot): Boolean {
        val eq = snap.state.equalizer ?: return false
        val spec = snap.capabilities?.equalizer ?: return false
        return eq.presetCode != EqualizerPreset.CUSTOM.code && spec.presets.none { it.code == eq.presetCode }
    }

    private fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (e: ProfileFormatException) {
            _ui.update { it.copy(message = e.message) }
        } catch (e: IllegalArgumentException) {
            _ui.update { it.copy(message = e.message) }
        }
        refreshProfiles()
    }

    fun saveCurrentAs(name: String) = attempt {
        val setting = currentSetting() ?: return@attempt
        val steps = setting.steps
        val reported = _ui.value.snapshot.state.equalizer?.bands.orEmpty()
        val base = _ui.value.profiles.firstOrNull { setting.presetCode != null && it.deviceEq.presetCode == setting.presetCode }
        val reportedClearBass = _ui.value.snapshot.state.equalizer?.clearBass
        val saved = when {
            steps != null -> library.saveCurve(name, modelId(), steps, clearBass = setting.clearBass)
            base != null -> library.duplicate(base.id, name)
            // A Sound Connect user setting: keep its curve as a custom profile.
            reported.size == _ui.value.snapshot.capabilities?.equalizer?.bandCount ->
                library.saveCurve(
                    name, modelId(), reported, clearBass = reportedClearBass,
                    notes = "Importado de un ajuste de usuario de Sound Connect (preset 0x%02x).".format(setting.presetCode),
                )
            else -> return@attempt
        }
        settings.lastAppliedProfileId = saved.id
    }

    fun duplicateProfile(profile: EqProfile, name: String) = attempt { library.duplicate(profile.id, name) }
    fun renameProfile(profile: EqProfile, name: String) = attempt { library.rename(profile.id, name) }
    fun deleteProfile(profile: EqProfile) = attempt { library.delete(profile.id) }

    fun overwriteWithCurrent(profile: EqProfile) = attempt {
        val current = currentSetting() ?: return@attempt
        library.updateSteps(profile.id, current.steps ?: return@attempt, current.clearBass)
    }

    fun exportProfiles(): String = library.exportText()

    fun importProfiles(text: String) = attempt {
        val imported = library.importText(text)
        _ui.update { it.copy(message = "imported:${imported.size}") }
    }

    fun previewAutoEq(text: String, name: String) {
        val model = _ui.value.snapshot.target?.model ?: HeadphoneModel.WH_1000XM6
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { AutoEqImporter.fit(text, model) }
                _ui.update { it.copy(autoEqPreview = AutoEqPreview(name.ifBlank { "AutoEQ" }, result.fit, result.sourceCurve)) }
            } catch (e: ProfileFormatException) {
                _ui.update { it.copy(message = e.message) }
            }
        }
    }

    fun dismissAutoEq() = _ui.update { it.copy(autoEqPreview = null) }

    fun saveAutoEq(apply: Boolean) {
        val preview = _ui.value.autoEqPreview ?: return
        attempt {
            val saved = library.saveCurve(
                name = preview.name,
                modelId = modelId(),
                steps = preview.fit.steps,
                category = ProfileCategory.MEASURED,
                sourceCurve = preview.sourceCurve,
                source = ProfileSource(description = "AutoEq import, fitted to the headphones' ${preview.fit.steps.size} bands (prediction)"),
                notes = "Error RMS ponderado %.2f dB".format(preview.fit.weightedRmsErrorDb),
            )
            if (apply) applyProfile(saved)
        }
        dismissAutoEq()
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    // endregion

    companion object {
        const val DEMO_ADDRESS = "00:00:00:00:DE:40"
        private const val SURFACE_ID = "main-activity"
        private const val MAX_HISTORY = 30
    }
}
