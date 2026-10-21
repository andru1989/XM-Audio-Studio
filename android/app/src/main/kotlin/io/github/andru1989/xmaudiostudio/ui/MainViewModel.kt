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

    fun setNoiseMode(mode: AmbientSoundMode) {
        val a = ambient()
        session.execute(DeviceCommand.SetAmbientSound(mode, a?.focusOnVoice ?: false, (a?.level ?: 15).coerceAtLeast(1)))
    }

    fun setAmbientLevel(level: Int) {
        val a = ambient()
        session.execute(DeviceCommand.SetAmbientSound(AmbientSoundMode.AMBIENT_SOUND, a?.focusOnVoice ?: false, level))
    }

    fun setFocusOnVoice(enabled: Boolean) {
        val a = ambient()
        session.execute(DeviceCommand.SetAmbientSound(AmbientSoundMode.AMBIENT_SOUND, enabled, (a?.level ?: 15).coerceAtLeast(1)))
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
        return if (eq.presetCode == EqualizerPreset.CUSTOM.code) DeviceEqSetting(steps = eq.bands) else DeviceEqSetting(presetCode = eq.presetCode)
    }

    private fun remember(previous: DeviceEqSetting?) {
        if (previous == null) return
        if (history.lastOrNull() != previous) history.addLast(previous)
        while (history.size > MAX_HISTORY) history.removeFirst()
        compareWith = previous
        _ui.update { it.copy(canUndo = history.isNotEmpty(), compareWith = compareWith) }
    }

    private fun send(setting: DeviceEqSetting) {
        setting.presetCode?.let { session.execute(DeviceCommand.SetEqualizerPreset(it)) }
        setting.steps?.let { session.execute(DeviceCommand.SetEqualizerBands(it)) }
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
        session.execute(DeviceCommand.SetEqualizerBands(updated))
    }

    fun finishBandEdit() {
        remember(dragOrigin)
        dragOrigin = null
    }

    fun resetFlat() {
        val count = _ui.value.snapshot.capabilities?.equalizer?.bandCount ?: return
        remember(currentSetting())
        settings.lastAppliedProfileId = "builtin.flat"
        session.execute(DeviceCommand.SetEqualizerBands(List(count) { 0 }))
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
        val candidates = if (eq.presetCode == EqualizerPreset.CUSTOM.code) {
            all.filter { it.deviceEq.steps == eq.bands }
        } else {
            all.filter { it.deviceEq.presetCode == eq.presetCode }
        }
        return candidates.firstOrNull { it.id == settings.lastAppliedProfileId } ?: candidates.firstOrNull()
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
        val saved = if (steps != null) {
            library.saveCurve(name, modelId(), steps)
        } else {
            val base = _ui.value.profiles.first { it.deviceEq.presetCode == setting.presetCode }
            library.duplicate(base.id, name)
        }
        settings.lastAppliedProfileId = saved.id
    }

    fun duplicateProfile(profile: EqProfile, name: String) = attempt { library.duplicate(profile.id, name) }
    fun renameProfile(profile: EqProfile, name: String) = attempt { library.rename(profile.id, name) }
    fun deleteProfile(profile: EqProfile) = attempt { library.delete(profile.id) }

    fun overwriteWithCurrent(profile: EqProfile) = attempt {
        val steps = currentSetting()?.steps ?: return@attempt
        library.updateSteps(profile.id, steps)
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
                source = ProfileSource(description = "AutoEq import, fitted to the headphones' 10 bands (prediction)"),
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
