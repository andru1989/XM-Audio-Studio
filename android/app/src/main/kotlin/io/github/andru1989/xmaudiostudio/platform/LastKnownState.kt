package io.github.andru1989.xmaudiostudio.platform

import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.session.SessionSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The tiny snapshot the widget shows as "último estado conocido" after the control channel was
 * released or the process was killed. Only what the widget renders is kept: nothing here is ever
 * written back to the headphones (rule 3: read the real state before writing).
 */
@Serializable
data class LastKnownState(
    /** Bluetooth address of the headphones it describes (the demo has its own fake address). */
    val address: String,
    val modelName: String,
    val mode: AmbientSoundMode? = null,
    val batteryLevel: Int? = null,
    val charging: Boolean = false,
    /** When this state was last confirmed by the headphones (epoch ms). */
    val timestampMs: Long,
) {
    fun sameContentAs(other: LastKnownState?): Boolean =
        other != null && copy(timestampMs = 0) == other.copy(timestampMs = 0)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null for missing or unreadable data (e.g. written by a future version). */
        fun decode(text: String?): LastKnownState? =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }

        /** A record of [snapshot] if it carries live state worth keeping, else null. */
        fun from(snapshot: SessionSnapshot, nowMs: Long): LastKnownState? {
            val target = snapshot.target ?: return null
            if (!snapshot.stateIsLive) return null
            val state = snapshot.state
            if (state.ambientSound == null && state.battery == null) return null
            return LastKnownState(
                address = target.address,
                modelName = target.model.displayName,
                mode = state.ambientSound?.mode,
                batteryLevel = state.battery?.level,
                charging = state.battery?.isCharging == true,
                timestampMs = nowMs,
            )
        }
    }
}

/**
 * Decides when to persist: whenever the live content changes, and once more when the state stops
 * being live (channel released, yielded or failed), stamped with that moment, because the values
 * were confirmed up to then.
 */
class LastKnownTracker {
    private var live: LastKnownState? = null

    /** Returns the record to persist for this snapshot, or null when nothing changed. */
    fun onSnapshot(snapshot: SessionSnapshot, nowMs: Long): LastKnownState? {
        val current = LastKnownState.from(snapshot, nowMs)
        if (current != null) {
            if (current.sameContentAs(live)) return null
            live = current
            return current
        }
        val ended = live ?: return null
        live = null
        return ended.copy(timestampMs = nowMs)
    }
}
