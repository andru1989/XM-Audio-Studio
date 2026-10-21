package io.github.andru1989.xmaudiostudio.ui.scenes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.presets.EqProfile
import io.github.andru1989.xmaudiostudio.core.presets.SceneChange
import io.github.andru1989.xmaudiostudio.ui.MainViewModel
import io.github.andru1989.xmaudiostudio.ui.UiState
import io.github.andru1989.xmaudiostudio.ui.theme.Studio

/** Flat pill shown next to the name of a profile that carries a scene. */
@Composable
fun SceneBadge(modifier: Modifier = Modifier) {
    val c = Studio.colors
    val description = stringResource(R.string.scene_badge_description)
    Text(
        stringResource(R.string.scene_badge),
        modifier = modifier
            .background(c.deviceEq.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = description },
        color = c.deviceEq,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
    )
}

/**
 * "Guardar configuración completa": asks for a name, then saves the EQ plus the headphone
 * settings as one profile. Disabled (with the reason) until the state was read live.
 */
@Composable
fun SaveSceneButton(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier, showDetail: Boolean = true) {
    var naming by remember { mutableStateOf(false) }
    val enabled = vm.canSaveScene(state.snapshot)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { naming = true }, enabled = enabled) {
            Icon(Icons.Filled.Tune, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.scene_save_full))
        }
        if (showDetail || !enabled) {
            Text(
                stringResource(if (enabled) R.string.scene_save_full_detail else R.string.scene_save_needs_live),
                color = Studio.colors.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    if (naming) {
        val suggested = stringResource(R.string.scene_default_name)
        var name by remember { mutableStateOf(suggested) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.scene_save_full)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.scene_save_full_detail), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveScene(name); naming = false }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Confirmation before applying a scene profile: every setting that changes, then the EQ. */
@Composable
fun ApplySceneDialog(profile: EqProfile, state: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    val c = Studio.colors
    val context = LocalContext.current
    val changes = vm.sceneChanges(profile)
    val eqName = profile.deviceEq.presetCode?.let { code ->
        state.snapshot.capabilities?.equalizer?.presets?.firstOrNull { it.code == code }?.name ?: "0x%02x".format(code)
    } ?: stringResource(R.string.scene_eq_curve)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scene_apply_title, profile.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.scene_apply_intro), style = MaterialTheme.typography.bodyMedium)
                changes.forEach { Text("• " + changeText(context, it), style = MaterialTheme.typography.bodyMedium) }
                Text("• " + stringResource(R.string.scene_change_eq, eqName), style = MaterialTheme.typography.bodyMedium)
                if (changes.isEmpty()) Text(stringResource(R.string.scene_apply_nothing_else), color = c.muted, style = MaterialTheme.typography.bodySmall)
                if (vm.sceneLeavesEqInactive(profile)) Text(stringResource(R.string.eq_disabled_listening), color = c.warning, style = MaterialTheme.typography.bodySmall)
                if (!state.snapshot.stateIsLive) Text(stringResource(R.string.scene_apply_not_live), color = c.muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { vm.applyProfile(profile); onDismiss() }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One confirmation line, e.g. "Control de ruido: Ambiente → Cancelación". */
private fun changeText(context: Context, change: SceneChange): String {
    fun text(id: Int) = context.getString(id)
    val unknown = text(R.string.scene_value_unknown)
    fun line(label: Int, from: String, to: String) = context.getString(R.string.scene_change, text(label), from, to)
    fun onOff(value: Boolean?) = when (value) {
        true -> text(R.string.scene_value_on)
        false -> text(R.string.scene_value_off)
        null -> unknown
    }
    fun noise(mode: AmbientSoundMode?) = when (mode) {
        AmbientSoundMode.NOISE_CANCELLING -> text(R.string.nc)
        AmbientSoundMode.AMBIENT_SOUND -> text(R.string.ambient)
        AmbientSoundMode.OFF -> text(R.string.off)
        null -> unknown
    }
    fun listening(mode: ListeningMode?) = when (mode) {
        ListeningMode.STANDARD -> text(R.string.lm_standard)
        ListeningMode.BACKGROUND_MUSIC -> text(R.string.lm_bgm)
        ListeningMode.CINEMA -> text(R.string.lm_cinema)
        null -> unknown
    }
    fun room(size: BgmRoomSize?) = when (size) {
        BgmRoomSize.SMALL -> text(R.string.room_small)
        BgmRoomSize.MIDDLE -> text(R.string.room_middle)
        BgmRoomSize.LARGE -> text(R.string.room_large)
        null -> unknown
    }
    // The Settings switch reads "Apagar al quitarse los audífonos": on means "when taken off".
    fun apo(mode: AutomaticPowerOffMode?) = onOff(mode?.let { it == AutomaticPowerOffMode.WHEN_TAKEN_OFF })
    fun level(value: Int?) = value?.toString() ?: unknown

    return when (change) {
        is SceneChange.NoiseMode -> line(R.string.noise_control, noise(change.from), noise(change.to))
        is SceneChange.AmbientLevel -> line(R.string.ambient_level, level(change.from), level(change.to))
        is SceneChange.FocusOnVoice -> line(R.string.focus_on_voice, onOff(change.from), onOff(change.to))
        is SceneChange.Listening -> line(R.string.listening_mode, listening(change.from), listening(change.to))
        is SceneChange.RoomSize -> line(R.string.scene_bgm_room, room(change.from), room(change.to))
        is SceneChange.SpeakToChat -> line(R.string.speak_to_chat, onOff(change.from), onOff(change.to))
        is SceneChange.PauseWhenTakenOff -> line(R.string.pause_when_off, onOff(change.from), onOff(change.to))
        is SceneChange.AutoPowerOff -> line(R.string.auto_power_off, apo(change.from), apo(change.to))
    }
}
