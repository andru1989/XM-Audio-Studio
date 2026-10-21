package io.github.andru1989.xmaudiostudio.ui.equalizer

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.core.device.EqualizerSpec
import io.github.andru1989.xmaudiostudio.core.device.Feature
import io.github.andru1989.xmaudiostudio.core.eq.FrequencyGrid
import io.github.andru1989.xmaudiostudio.core.model.EqualizerPreset
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.presets.AutoEqImporter
import io.github.andru1989.xmaudiostudio.core.presets.EqProfile
import io.github.andru1989.xmaudiostudio.core.presets.ProfileCategory
import io.github.andru1989.xmaudiostudio.core.session.ConnectionPhase
import io.github.andru1989.xmaudiostudio.ui.AutoEqPreview
import io.github.andru1989.xmaudiostudio.ui.MainViewModel
import io.github.andru1989.xmaudiostudio.ui.UiState
import io.github.andru1989.xmaudiostudio.ui.components.SectionCard
import io.github.andru1989.xmaudiostudio.ui.components.StatusDot
import io.github.andru1989.xmaudiostudio.ui.theme.Studio

private val chartGrid = FrequencyGrid.logSpaced(20.0, 20_000.0, 160)

private fun predicted(spec: EqualizerSpec, steps: List<Int>) = AutoEqImporter.deviceModel(spec).responseDb(steps, chartGrid)

@Composable
fun EqualizerScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val snap = state.snapshot
    val caps = snap.capabilities
    val spec = caps?.equalizer
    val c = Studio.colors
    val context = LocalContext.current

    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var autoEqDialog by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportProfiles().toByteArray()) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use { vm.importProfiles(it.readBytes().decodeToString()) }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_eq), style = MaterialTheme.typography.headlineMedium)
        if (spec == null || caps.supports(Feature.EQ_CUSTOM_BANDS).not()) {
            SectionCard { Text(stringResource(R.string.no_device)) }
            return@Column
        }

        // Which equalizer is being edited, always visible: the headphones' own EQ (blue) vs local DSP (amber).
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(c.deviceEq)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.eq_destination_device, snap.target?.model?.displayName ?: ""), style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(c.dspEq.copy(alpha = 0.4f))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.eq_destination_dsp), color = c.muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        val eq = snap.state.equalizer
        val steps = eq?.bands?.takeIf { it.size == spec.bandCount } ?: List(spec.bandCount) { 0 }
        val listeningBlocksEq = snap.state.listeningMode != null && snap.state.listeningMode != ListeningMode.STANDARD
        val editable = eq != null && !listeningBlocksEq && snap.phase != ConnectionPhase.FAILED
        val compareSteps = state.compareWith?.steps
        val target = state.activeProfile?.sourceCurve?.toParametric()?.responseDb(chartGrid)

        val importable = eq != null && eq.bands.size == spec.bandCount &&
            (eq.presetCode == EqualizerPreset.CUSTOM.code || vm.isUserPresetFromOfficialApp(snap))
        if (snap.stateIsLive && importable && state.activeProfile == null) {
            val suggested = stringResource(R.string.device_curve_name)
            SectionCard {
                Text(stringResource(R.string.device_curve_not_saved), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = { vm.saveCurrentAs(suggested) }) { Text(stringResource(R.string.save_to_library)) }
            }
        }

        SectionCard {
            val curves = buildList {
                if (target != null) add(ChartCurve(chartGrid, target, c.target, dashed = false, width = 1.8f))
                if (compareSteps != null && compareSteps.size == spec.bandCount) add(ChartCurve(chartGrid, predicted(spec, compareSteps), c.muted.copy(alpha = 0.6f), dashed = true, width = 1.5f))
                add(ChartCurve(chartGrid, predicted(spec, steps), c.deviceEq, dashed = true))
            }
            ResponseChart(curves, spec.bandFrequenciesHz, steps.map { it * spec.stepDb }, rangeDb = 12.0)
            Text(stringResource(R.string.eq_prediction_note), color = c.muted, style = MaterialTheme.typography.labelSmall)
            if (!spec.frequenciesVerified) Text(stringResource(R.string.eq_unverified_freqs), color = c.muted, style = MaterialTheme.typography.labelSmall)

            val clearBassRange = spec.clearBassRange
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceBetween) {
                // 5-band models (XM3-XM5): Clear Bass is its own control, sent with the bands.
                if (clearBassRange != null) {
                    VerticalFader(
                        value = (eq?.clearBass ?: 0).coerceIn(clearBassRange),
                        range = clearBassRange,
                        label = stringResource(R.string.clear_bass),
                        enabled = editable,
                        onChange = vm::setClearBass,
                        onFinish = vm::finishBandEdit,
                    )
                }
                spec.bandFrequenciesHz.forEachIndexed { i, f ->
                    VerticalFader(
                        value = steps[i],
                        range = spec.minStep..spec.maxStep,
                        label = frequencyLabel(f),
                        enabled = editable,
                        onChange = { vm.setBand(i, it) },
                        onFinish = vm::finishBandEdit,
                    )
                }
            }
            if (clearBassRange != null) Text(stringResource(R.string.clear_bass_note), color = c.muted, style = MaterialTheme.typography.labelSmall)
            when {
                listeningBlocksEq -> Text(stringResource(R.string.eq_disabled_listening), color = c.warning, style = MaterialTheme.typography.bodySmall)
                eq != null && eq.presetCode != EqualizerPreset.CUSTOM.code -> {
                    val name = spec.presets.firstOrNull { it.code == eq.presetCode }?.name ?: "0x%02x".format(eq.presetCode)
                    Text(stringResource(R.string.eq_preset_active, name), color = c.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            val maxBoost = (steps.maxOrNull() ?: 0) * spec.stepDb
            if (maxBoost >= 4) Text(stringResource(R.string.eq_clip_risk, maxBoost.toInt()), color = c.warning, style = MaterialTheme.typography.bodySmall)

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::resetFlat, enabled = editable) { Text(stringResource(R.string.reset_flat)) }
                OutlinedButton(onClick = vm::undo, enabled = editable && state.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.undo))
                }
                OutlinedButton(onClick = vm::toggleCompare, enabled = editable && state.compareWith != null) {
                    Icon(Icons.AutoMirrored.Filled.CompareArrows, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ab))
                }
                FilledTonalButton(onClick = { nameDialog = NameDialog.SaveAs }, enabled = eq != null) { Text(stringResource(R.string.save_as)) }
            }
        }

        SectionCard(stringResource(R.string.profiles)) {
            ProfileCategory.entries.forEach { category ->
                val inCategory = state.profiles.filter { it.category == category }
                if (inCategory.isEmpty()) return@forEach
                Text(categoryLabel(category), color = c.muted, style = MaterialTheme.typography.labelMedium)
                inCategory.forEach { p ->
                    ProfileRow(
                        profile = p,
                        active = p.id == state.activeProfile?.id,
                        enabled = !listeningBlocksEq,
                        onApply = { vm.applyProfile(p) },
                        onDuplicate = { nameDialog = NameDialog.Duplicate(p) },
                        onRename = { nameDialog = NameDialog.Rename(p) },
                        onDelete = { vm.deleteProfile(p) },
                        onOverwrite = { vm.overwriteWithCurrent(p) },
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { autoEqDialog = true }) { Text(stringResource(R.string.autoeq_import)) }
                OutlinedButton(onClick = { exportLauncher.launch("xm-audio-studio-profiles.json") }) { Text(stringResource(R.string.export_profiles)) }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text(stringResource(R.string.import_profiles)) }
            }
        }
    }

    nameDialog?.let { dialog ->
        NamePrompt(
            initial = when (dialog) {
                is NameDialog.Duplicate -> dialog.profile.name + " (2)"
                is NameDialog.Rename -> dialog.profile.name
                NameDialog.SaveAs -> ""
            },
            onDismiss = { nameDialog = null },
        ) { name ->
            when (dialog) {
                is NameDialog.Duplicate -> vm.duplicateProfile(dialog.profile, name)
                is NameDialog.Rename -> vm.renameProfile(dialog.profile, name)
                NameDialog.SaveAs -> vm.saveCurrentAs(name)
            }
            nameDialog = null
        }
    }
    if (autoEqDialog) {
        AutoEqInputDialog(onDismiss = { autoEqDialog = false }) { text, name ->
            autoEqDialog = false
            vm.previewAutoEq(text, name)
        }
    }
    state.autoEqPreview?.let { preview -> if (spec != null) AutoEqPreviewDialog(preview, spec, vm) }
}

private sealed interface NameDialog {
    data object SaveAs : NameDialog
    data class Duplicate(val profile: EqProfile) : NameDialog
    data class Rename(val profile: EqProfile) : NameDialog
}

@Composable
private fun categoryLabel(category: ProfileCategory) = stringResource(
    when (category) {
        ProfileCategory.DEVICE_PRESET -> R.string.cat_device_preset
        ProfileCategory.MEASURED -> R.string.cat_measured
        ProfileCategory.SUBJECTIVE -> R.string.cat_subjective
        ProfileCategory.USER -> R.string.cat_user
    },
)

@Composable
private fun ProfileRow(
    profile: EqProfile,
    active: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOverwrite: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).combinedClickable(enabled = enabled, onClick = onApply, onLongClick = { menu = true }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) Icon(Icons.Filled.Check, null, tint = Studio.colors.deviceEq, modifier = Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.name, style = MaterialTheme.typography.bodyLarge)
            profile.notes?.let { Text(it, color = Studio.colors.muted, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
        }
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.apply)) }, { menu = false; onApply() }, enabled = enabled)
            DropdownMenuItem({ Text(stringResource(R.string.duplicate)) }, { menu = false; onDuplicate() })
            if (!profile.isBuiltin) {
                DropdownMenuItem({ Text(stringResource(R.string.rename)) }, { menu = false; onRename() })
                if (profile.deviceEq.steps != null) DropdownMenuItem({ Text(stringResource(R.string.overwrite)) }, { menu = false; onOverwrite() })
                DropdownMenuItem({ Text(stringResource(R.string.delete)) }, { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun NamePrompt(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.name)) },
        text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AutoEqInputDialog(onDismiss: () -> Unit, onFit: (String, String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("AutoEQ") }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use { text = it.readBytes().decodeToString() }
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.autoeq_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.autoeq_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp), textStyle = MaterialTheme.typography.bodySmall)
                AssistChip(onClick = { fileLauncher.launch(arrayOf("text/plain", "*/*")) }, label = { Text(stringResource(R.string.autoeq_open_file)) })
            }
        },
        confirmButton = { TextButton(onClick = { onFit(text, name) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.autoeq_fit)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AutoEqPreviewDialog(preview: AutoEqPreview, spec: EqualizerSpec, vm: MainViewModel) {
    val c = Studio.colors
    val fit = preview.fit
    AlertDialog(
        onDismissRequest = vm::dismissAutoEq,
        title = { Text(stringResource(R.string.autoeq_preview) + " · " + preview.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Target shifted by the free level offset so both curves share a reference.
                val target = fit.targetDb.map { it - fit.levelOffsetDb }.toDoubleArray()
                ResponseChart(
                    listOf(ChartCurve(fit.frequencies, target, c.target, dashed = false, width = 1.8f), ChartCurve(fit.frequencies, fit.predictedDb, c.deviceEq, dashed = true)),
                    spec.bandFrequenciesHz, fit.steps.map { it * spec.stepDb }, rangeDb = 12.0,
                )
                Text(fit.steps.joinToString("  ") { if (it > 0) "+$it" else "$it" }, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.autoeq_error, fit.weightedRmsErrorDb, fit.maxAbsErrorDb), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.autoeq_limits), color = c.muted, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveAutoEq(apply = true) }) { Text(stringResource(R.string.save_apply)) } },
        dismissButton = {
            Row {
                TextButton(onClick = vm::dismissAutoEq) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { vm.saveAutoEq(apply = false) }) { Text(stringResource(R.string.save)) }
            }
        },
    )
}
