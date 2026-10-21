package io.github.andru1989.xmaudiostudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.core.session.ConnectionPhase
import io.github.andru1989.xmaudiostudio.core.session.SessionSnapshot
import io.github.andru1989.xmaudiostudio.ui.theme.Studio

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Studio.colors.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T?, enabled: Boolean = true, onSelect: (T) -> Unit) {
    // Every segment takes the height of the tallest one, so a label that wraps to two lines
    // ("Música de fondo") does not leave its neighbours shorter.
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                modifier = Modifier.fillMaxHeight(),
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label, maxLines = 2, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) },
            )
        }
    }
}

@Composable
fun StatusDot(color: Color) {
    Spacer(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
fun phaseLabel(phase: ConnectionPhase): String = stringResource(
    when (phase) {
        ConnectionPhase.IDLE -> R.string.phase_idle
        ConnectionPhase.CONNECTING -> R.string.phase_connecting
        ConnectionPhase.INITIALIZING -> R.string.phase_initializing
        ConnectionPhase.READING_STATE -> R.string.phase_reading
        ConnectionPhase.CONNECTED -> R.string.phase_connected
        ConnectionPhase.YIELDED -> R.string.phase_yielded
        ConnectionPhase.FAILED -> R.string.phase_failed
    },
)

/** Control-channel status (never the audio link), with the actions that make sense for it. */
@Composable
fun ConnectionCard(snapshot: SessionSnapshot, onConnect: () -> Unit, onDisconnect: () -> Unit, onRefresh: () -> Unit) {
    val c = Studio.colors
    val phase = snapshot.phase
    val busy = phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.INITIALIZING || phase == ConnectionPhase.READING_STATE
    val color = when (phase) {
        ConnectionPhase.CONNECTED -> c.success
        ConnectionPhase.FAILED -> c.danger
        ConnectionPhase.YIELDED -> c.warning
        else -> c.muted
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else StatusDot(color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(snapshot.target?.name ?: "—", style = MaterialTheme.typography.titleMedium)
                Text(phaseLabel(phase), color = c.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        val info = snapshot.state.deviceInfo
        if (info.firmwareVersion != null || info.modelName != null) {
            Text(
                listOfNotNull(info.modelName, info.firmwareVersion?.let { stringResource(R.string.firmware, it) }).joinToString(" · "),
                color = c.muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        when {
            phase == ConnectionPhase.IDLE && snapshot.target != null ->
                Text(stringResource(R.string.phase_idle_detail), color = c.muted, style = MaterialTheme.typography.bodySmall)
            snapshot.error != null -> Text(snapshot.error!!.message, color = if (phase == ConnectionPhase.YIELDED) c.warning else c.danger, style = MaterialTheme.typography.bodySmall)
            snapshot.initialStateTimedOut -> Text(stringResource(R.string.state_timeout), color = c.warning, style = MaterialTheme.typography.bodySmall)
        }
        if (!snapshot.stateIsLive && snapshot.state.ambientSound != null) {
            Text(stringResource(R.string.state_not_live), color = c.muted, style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (phase) {
                ConnectionPhase.CONNECTED -> {
                    OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
                    OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
                }
                ConnectionPhase.FAILED, ConnectionPhase.YIELDED -> Button(onClick = onConnect) { Text(stringResource(R.string.retry)) }
                ConnectionPhase.IDLE -> if (snapshot.target != null) Button(onClick = onConnect) { Text(stringResource(R.string.connect)) }
                else -> Unit
            }
        }
    }
}
