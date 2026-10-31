package io.github.andru1989.xmaudiostudio.ui.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.core.device.DeviceCapabilities
import io.github.andru1989.xmaudiostudio.core.device.Feature
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneState
import io.github.andru1989.xmaudiostudio.core.device.SourceAdvice
import io.github.andru1989.xmaudiostudio.core.model.MultipointDevice
import io.github.andru1989.xmaudiostudio.core.model.PlaybackAction
import io.github.andru1989.xmaudiostudio.core.model.PlaybackState
import io.github.andru1989.xmaudiostudio.core.model.SourceSwitchResult
import io.github.andru1989.xmaudiostudio.ui.MainViewModel
import io.github.andru1989.xmaudiostudio.ui.components.SectionCard
import io.github.andru1989.xmaudiostudio.ui.theme.Studio

/**
 * "Dónde suena": the device the headphones' buttons act on (playback right), its play state and
 * track, transport controls relayed through the headphones, and the other devices with a one-tap
 * switch. Warns when this phone plays but the controls are elsewhere, or when music was cut off
 * by a handover, with the fix one tap away.
 */
@Composable
fun PlaybackSourceCard(s: HeadphoneState, caps: DeviceCapabilities, advice: SourceAdvice?, vm: MainViewModel) {
    val devices = s.devices.orEmpty()
    val active = s.playbackDevice
    val thisMac = advice?.thisDevice?.macAddress
    val canSwitch = caps.supports(Feature.PLAYBACK_SOURCE_SWITCH)

    SectionCard(stringResource(R.string.source_title)) {
        val interrupted = advice?.interrupted
        if (interrupted != null && canSwitch) {
            val toName = devices.firstOrNull { it.macAddress == interrupted.toMac }?.name ?: stringResource(R.string.source_other_device)
            Banner(
                text = stringResource(R.string.source_interrupted, interrupted.fromName, toName),
                action = stringResource(R.string.source_resume_on, interrupted.fromName),
                onAction = { vm.recoverPlayback(interrupted.fromMac) },
            )
        } else if (advice?.controlsElsewhere == true && canSwitch && thisMac != null && active != null) {
            Banner(
                text = stringResource(R.string.source_controls_elsewhere, active.name),
                action = stringResource(R.string.source_bring_controls),
                onAction = { vm.switchPlayback(thisMac) },
            )
        }
        s.sourceSwitchFailure?.let { failure ->
            Text(switchFailureText(failure), color = Studio.colors.warning, style = MaterialTheme.typography.bodySmall)
        }

        if (active == null) {
            Text(stringResource(R.string.source_none), color = Studio.colors.muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            ActiveSource(s, active, isThisDevice = active.macAddress == thisMac)
            val status = s.playbackStatus
            if (caps.supports(Feature.PLAYBACK_CONTROL) && status != null && status.controlsAvailable && !status.isCall) {
                Transport(playing = status.state == PlaybackState.PLAYING, onAction = vm::controlPlayback)
            }
        }

        val others = devices.filter { it.macAddress != active?.macAddress }
        if (others.isNotEmpty()) {
            HorizontalDivider(color = Studio.colors.gridLine)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                others.forEach { d ->
                    OtherDevice(
                        device = d,
                        isThisDevice = d.macAddress == thisMac,
                        switching = s.switchingTo == d.macAddress,
                        canSwitch = canSwitch && d.isConnected && s.switchingTo == null,
                        onSwitch = { vm.switchPlayback(d.macAddress) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveSource(s: HeadphoneState, active: MultipointDevice, isThisDevice: Boolean) {
    val c = Studio.colors
    val status = s.playbackStatus
    val playing = status?.state == PlaybackState.PLAYING && !status.isCall
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).background(c.deviceEq.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(deviceIcon(active.name), contentDescription = null, tint = c.deviceEq, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    active.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isThisDevice) {
                    Spacer(Modifier.width(8.dp))
                    Tag(stringResource(R.string.source_this_phone))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing) {
                    EqualizerBars(c.success)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    stateLabel(s), color = if (playing) c.success else c.muted,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                )
            }
            val track = s.nowPlaying?.let { n -> listOfNotNull(n.title, n.artist).joinToString(" — ") }.orEmpty()
            if (track.isNotEmpty()) {
                Text(track, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun stateLabel(s: HeadphoneState): String {
    val status = s.playbackStatus
    return stringResource(
        when {
            status == null -> R.string.source_controls_here
            status.isCall -> R.string.source_call
            status.state == PlaybackState.PLAYING -> R.string.source_playing
            status.state == PlaybackState.PAUSED -> R.string.source_paused
            status.state == PlaybackState.STOPPED -> R.string.source_stopped
            else -> R.string.source_controls_here
        },
    )
}

@Composable
private fun Transport(playing: Boolean, onAction: (PlaybackAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onAction(PlaybackAction.PREVIOUS) }) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.playback_previous))
        }
        Spacer(Modifier.width(12.dp))
        FilledTonalIconButton(onClick = { onAction(if (playing) PlaybackAction.PAUSE else PlaybackAction.PLAY) }, modifier = Modifier.size(52.dp)) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.playback_pause else R.string.playback_play),
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = { onAction(PlaybackAction.NEXT) }) {
            Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.playback_next))
        }
    }
}

@Composable
private fun OtherDevice(device: MultipointDevice, isThisDevice: Boolean, switching: Boolean, canSwitch: Boolean, onSwitch: () -> Unit) {
    val c = Studio.colors
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            deviceIcon(device.name), contentDescription = null,
            tint = if (device.isConnected) c.onSurface else c.muted, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (device.isConnected) c.onSurface else c.muted, modifier = Modifier.weight(1f, fill = false),
                )
                if (isThisDevice) {
                    Spacer(Modifier.width(6.dp))
                    Tag(stringResource(R.string.source_this_phone))
                }
            }
            Text(
                stringResource(if (device.isConnected) R.string.source_connected else R.string.source_paired),
                color = c.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            )
        }
        when {
            switching -> Box(Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            canSwitch -> TextButton(onClick = onSwitch) { Text(stringResource(R.string.play_here), maxLines = 1) }
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    val c = Studio.colors
    Column(
        Modifier.fillMaxWidth().background(c.warning.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = onAction) { Text(action, maxLines = 2) }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        color = Studio.colors.deviceEq,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier.background(Studio.colors.deviceEq.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Three bars bouncing out of phase: "sound is coming from here". */
@Composable
private fun EqualizerBars(color: Color) {
    val transition = rememberInfiniteTransition(label = "eq-bars")
    val periods = listOf(520, 680, 440)
    val heights = periods.mapIndexed { i, ms ->
        val h by transition.animateFloat(
            initialValue = 0.25f + 0.2f * i, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(ms), RepeatMode.Reverse), label = "bar$i",
        )
        h
    }
    Row(Modifier.height(12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEach { h ->
            Box(Modifier.width(3.dp).fillMaxHeight(h).background(color, RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
private fun switchFailureText(result: SourceSwitchResult): String = stringResource(
    when (result) {
        SourceSwitchResult.FAILED_CALLING -> R.string.source_failed_call
        SourceSwitchResult.FAILED_A2DP_NOT_CONNECTED -> R.string.source_failed_a2dp
        SourceSwitchResult.FAILED_VOICE_ASSISTANT -> R.string.source_failed_assistant
        else -> R.string.source_failed_unconfirmed
    },
)

/** A glyph guessed from the name the device advertises; never used for any decision. */
private fun deviceIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        "iphone" in n -> Icons.Rounded.PhoneIphone
        listOf("ipad", "tab", "pad").any { it in n } -> Icons.Rounded.Tablet
        "watch" in n -> Icons.Rounded.Watch
        listOf("tv", "bravia").any { it in n } -> Icons.Rounded.Tv
        listOf("macbook", "book", "laptop", "thinkpad").any { it in n } -> Icons.Rounded.Laptop
        listOf("imac", "mac", "pc", "desktop", "windows").any { it in n } -> Icons.Rounded.Computer
        listOf("phone", "galaxy", "pixel", "xiaomi", "redmi", "moto", "oneplus", "huawei", "honor", "oppo", "vivo", "nothing").any { it in n } ->
            Icons.Rounded.PhoneAndroid
        else -> Icons.Rounded.Devices
    }
}
