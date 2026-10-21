package io.github.andru1989.xmaudiostudio.core.device

import io.github.andru1989.xmaudiostudio.core.session.SessionSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Model-independent handle the UI talks to. The only implementation today is
 * [io.github.andru1989.xmaudiostudio.core.session.HeadphoneSession], which pairs the shared
 * MDR session machinery with a per-generation [ProtocolAdapter].
 */
interface HeadphoneDevice {
    val snapshot: StateFlow<SessionSnapshot>

    /** Queue a user intent; acquires the control channel on demand. */
    fun execute(command: DeviceCommand)

    /** Re-read everything from the headphones. */
    fun refresh()

    /** Explicit user request to take control now (also clears a "yielded" state). */
    fun connect()

    /** Explicit user request to release the control channel immediately. */
    fun disconnect()

    /** A screen/panel showing headphone controls became visible or hidden. */
    fun setSurfaceVisible(id: String, visible: Boolean)
}
