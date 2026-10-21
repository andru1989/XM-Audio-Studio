package io.github.andru1989.xmaudiostudio.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.bluetooth.PairedDevices
import io.github.andru1989.xmaudiostudio.core.device.Feature
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.device.ProtocolAdapter
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatSensitivity
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatTimeout
import io.github.andru1989.xmaudiostudio.ui.MainViewModel
import io.github.andru1989.xmaudiostudio.ui.UiState
import io.github.andru1989.xmaudiostudio.ui.components.SectionCard
import io.github.andru1989.xmaudiostudio.ui.components.Segmented
import io.github.andru1989.xmaudiostudio.ui.theme.Studio

@Composable
private fun ToggleRow(title: String, detail: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, color = Studio.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel, requestPermission: () -> Unit, modifier: Modifier = Modifier) {
    val snap = state.snapshot
    val caps = snap.capabilities
    val s = snap.state
    val c = Studio.colors
    val context = LocalContext.current

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium)

        SectionCard(stringResource(R.string.settings_device)) {
            ToggleRow(stringResource(R.string.settings_demo), stringResource(R.string.settings_demo_detail), state.demoMode) { vm.setDemoMode(it) }
            if (!state.demoMode) {
                when (val devices = state.devices) {
                    is PairedDevices.Result.Devices -> {
                        if (devices.devices.isEmpty()) Text(stringResource(R.string.settings_no_paired), color = c.muted)
                        devices.devices.filter { it.isAudio || it.model != null }.forEach { d ->
                            val selected = snap.target?.address == d.address
                            Row(Modifier.fillMaxWidth().clickable(enabled = d.model != null) { vm.selectDevice(d) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selected, onClick = { if (d.model != null) vm.selectDevice(d) }, enabled = d.model != null)
                                Column(Modifier.weight(1f)) {
                                    Text(d.name)
                                    val model = d.model
                                    when {
                                        model == null -> {
                                            Text(stringResource(R.string.settings_unknown_model), color = c.muted, style = MaterialTheme.typography.bodySmall)
                                            TextButton(onClick = { vm.selectDevice(d, HeadphoneModel.WH_1000XM6) }) { Text(stringResource(R.string.settings_treat_as_xm6)) }
                                        }
                                        ProtocolAdapter.forModel(model) == null ->
                                            Text(stringResource(R.string.settings_planned, model.displayName), color = c.warning, style = MaterialTheme.typography.bodySmall)
                                        else -> Text(model.displayName, color = c.muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    PairedDevices.Result.PermissionMissing -> Button(onClick = requestPermission) { Text(stringResource(R.string.permission_grant)) }
                    PairedDevices.Result.BluetoothOff -> Text(stringResource(R.string.bluetooth_off), color = c.warning)
                    else -> Unit
                }
            } else {
                OutlinedButton(onClick = vm::simulateButtonPress) { Text(stringResource(R.string.demo_button)) }
                OutlinedButton(onClick = vm::simulateTakeover) { Text(stringResource(R.string.demo_takeover)) }
            }
            ToggleRow(stringResource(R.string.settings_release_idle), stringResource(R.string.settings_release_idle_detail), state.releaseWhenIdle) { vm.setReleaseWhenIdle(it) }
        }

        if (caps != null && snap.target != null) {
            SectionCard(stringResource(R.string.settings_headphones)) {
                if (caps.supports(Feature.LISTENING_MODE) && s.listeningMode == ListeningMode.BACKGROUND_MUSIC) {
                    Text(stringResource(R.string.lm_bgm), style = MaterialTheme.typography.labelLarge)
                    Segmented(
                        listOf(BgmRoomSize.SMALL to stringResource(R.string.room_small), BgmRoomSize.MIDDLE to stringResource(R.string.room_middle), BgmRoomSize.LARGE to stringResource(R.string.room_large)),
                        s.bgmRoomSize,
                    ) { vm.setRoomSize(it) }
                }
                if (caps.supports(Feature.SPEAK_TO_CHAT)) {
                    ToggleRow(stringResource(R.string.speak_to_chat), stringResource(R.string.feature_unverified), s.speakToChatEnabled == true, s.speakToChatEnabled != null) { vm.setSpeakToChat(it) }
                    val config = s.speakToChatConfig
                    if (s.speakToChatEnabled == true && config != null) {
                        Text(stringResource(R.string.stc_sensitivity), style = MaterialTheme.typography.labelLarge)
                        Segmented(
                            listOf(SpeakToChatSensitivity.AUTO to stringResource(R.string.sens_auto), SpeakToChatSensitivity.HIGH to stringResource(R.string.sens_high), SpeakToChatSensitivity.LOW to stringResource(R.string.sens_low)),
                            config.sensitivity,
                        ) { vm.setSpeakToChatConfig(SpeakToChatConfig(it, config.timeout)) }
                        Text(stringResource(R.string.stc_timeout), style = MaterialTheme.typography.labelLarge)
                        Segmented(
                            listOf(SpeakToChatTimeout.SHORT to stringResource(R.string.to_short), SpeakToChatTimeout.STANDARD to stringResource(R.string.to_standard),
                                SpeakToChatTimeout.LONG to stringResource(R.string.to_long), SpeakToChatTimeout.OFF to stringResource(R.string.to_off)),
                            config.timeout,
                        ) { vm.setSpeakToChatConfig(SpeakToChatConfig(config.sensitivity, it)) }
                    }
                }
                if (caps.supports(Feature.WEAR_DETECTION)) {
                    ToggleRow(stringResource(R.string.pause_when_off), checked = s.pauseWhenTakenOff == true, enabled = s.pauseWhenTakenOff != null) { vm.setPauseWhenTakenOff(it) }
                }
                if (caps.supports(Feature.AUTO_POWER_OFF)) {
                    ToggleRow(stringResource(R.string.auto_power_off), checked = s.automaticPowerOff == AutomaticPowerOffMode.WHEN_TAKEN_OFF, enabled = s.automaticPowerOff != null) {
                        vm.setAutoPowerOff(if (it) AutomaticPowerOffMode.WHEN_TAKEN_OFF else AutomaticPowerOffMode.OFF)
                    }
                }
            }

            val devices = s.devices
            if (caps.supports(Feature.MULTIPOINT_LIST) && !devices.isNullOrEmpty()) {
                SectionCard(stringResource(R.string.multipoint)) {
                    devices.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name)
                                if (d.isPlayback) Text(stringResource(R.string.playing), color = c.success, style = MaterialTheme.typography.bodySmall)
                            }
                            if (!d.isPlayback && d.isConnected && caps.supports(Feature.PLAYBACK_SOURCE_SWITCH)) {
                                TextButton(onClick = { vm.switchPlayback(d.macAddress) }) { Text(stringResource(R.string.play_here)) }
                            }
                        }
                    }
                }
            }
        }

        SectionCard(stringResource(R.string.settings_diagnostics)) {
            ToggleRow(stringResource(R.string.settings_protocol_log), stringResource(R.string.settings_protocol_log_detail), state.protocolLogging) { vm.setProtocolLogging(it) }
            if (state.protocolLogging) {
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.protocolLogText())
                    context.startActivity(Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.settings_share_log)) }
            }
        }

        SectionCard(stringResource(R.string.about)) {
            Text("XM Audio Studio 0.1.0", style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.disclaimer), color = c.muted, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.photo_credits), color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
