package io.github.andru1989.xmaudiostudio.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.bluetooth.PairedDevices
import io.github.andru1989.xmaudiostudio.core.device.Feature
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.ui.MainViewModel
import io.github.andru1989.xmaudiostudio.ui.UiState
import io.github.andru1989.xmaudiostudio.ui.components.ConnectionCard
import io.github.andru1989.xmaudiostudio.ui.components.SectionCard
import io.github.andru1989.xmaudiostudio.ui.components.Segmented
import io.github.andru1989.xmaudiostudio.ui.theme.Studio

@Composable
fun DashboardScreen(
    state: UiState,
    vm: MainViewModel,
    requestPermission: () -> Unit,
    openEqualizer: () -> Unit,
    openSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap = state.snapshot
    val caps = snap.capabilities
    val s = snap.state
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        if (state.demoMode) Text(stringResource(R.string.demo_banner), color = Studio.colors.warning, style = MaterialTheme.typography.bodySmall)

        when (state.devices) {
            PairedDevices.Result.PermissionMissing -> if (!state.demoMode) {
                SectionCard(stringResource(R.string.permission_title)) {
                    Text(stringResource(R.string.permission_body), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = requestPermission) { Text(stringResource(R.string.permission_grant)) }
                }
            }
            PairedDevices.Result.BluetoothOff -> if (!state.demoMode) SectionCard { Text(stringResource(R.string.bluetooth_off)) }
            else -> Unit
        }

        if (snap.target == null) {
            SectionCard {
                Text(stringResource(R.string.no_device))
                TextButton(onClick = openSettings) { Text(stringResource(R.string.settings_choose_device)) }
            }
            return@Column
        }

        ConnectionCard(snap, vm::connect, vm::disconnect, vm::refresh)

        s.battery?.let { b ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.battery), Modifier.weight(1f))
                    Text("${b.level}%" + if (b.isCharging) " · " + stringResource(R.string.charging) else "", style = MaterialTheme.typography.titleMedium)
                }
                LinearProgressIndicator(progress = { b.level / 100f }, modifier = Modifier.fillMaxWidth(),
                    color = if (b.level <= 15) Studio.colors.danger else Studio.colors.success)
            }
        }

        if (caps?.supports(Feature.NOISE_CANCELLING) == true) {
            val a = s.ambientSound
            SectionCard(stringResource(R.string.noise_control)) {
                Segmented(
                    listOf(
                        AmbientSoundMode.NOISE_CANCELLING to stringResource(R.string.nc),
                        AmbientSoundMode.AMBIENT_SOUND to stringResource(R.string.ambient),
                        AmbientSoundMode.OFF to stringResource(R.string.off),
                    ),
                    a?.mode,
                ) { vm.setNoiseMode(it) }
                if (a?.mode == AmbientSoundMode.AMBIENT_SOUND) {
                    var dragging by remember { mutableStateOf(false) }
                    var local by remember { mutableFloatStateOf(a.level.toFloat()) }
                    val shown = if (dragging) local else a.level.toFloat()
                    Text("${stringResource(R.string.ambient_level)}: ${shown.toInt()}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = shown,
                        onValueChange = { dragging = true; local = it },
                        onValueChangeFinished = { dragging = false; vm.setAmbientLevel(local.toInt()) },
                        valueRange = caps.ambientLevelRange.first.toFloat()..caps.ambientLevelRange.last.toFloat(),
                        steps = caps.ambientLevelRange.last - caps.ambientLevelRange.first - 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.focus_on_voice), Modifier.weight(1f))
                        Switch(checked = a.focusOnVoice, onCheckedChange = vm::setFocusOnVoice)
                    }
                }
            }
        }

        if (caps?.supports(Feature.LISTENING_MODE) == true) {
            SectionCard(stringResource(R.string.listening_mode)) {
                Segmented(
                    listOf(
                        ListeningMode.STANDARD to stringResource(R.string.lm_standard),
                        ListeningMode.BACKGROUND_MUSIC to stringResource(R.string.lm_bgm),
                        ListeningMode.CINEMA to stringResource(R.string.lm_cinema),
                    ),
                    s.listeningMode,
                ) { vm.setListeningMode(it) }
            }
        }

        SectionCard(stringResource(R.string.active_profile)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.activeProfile?.name ?: if (s.equalizer != null) stringResource(R.string.custom_unsaved) else "—",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = openEqualizer) { Text(stringResource(R.string.open_eq)) }
            }
        }

        Text(stringResource(R.string.disclaimer), color = Studio.colors.muted, style = MaterialTheme.typography.bodySmall)
    }
}
