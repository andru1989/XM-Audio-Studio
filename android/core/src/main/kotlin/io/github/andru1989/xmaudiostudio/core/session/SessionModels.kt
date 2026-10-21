package io.github.andru1989.xmaudiostudio.core.session

import io.github.andru1989.xmaudiostudio.core.device.DeviceCapabilities
import io.github.andru1989.xmaudiostudio.core.device.DeviceCatalog
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneState

/** A paired device the user selected. The audio link and the control channel are separate things. */
data class DeviceTarget(val address: String, val name: String, val model: HeadphoneModel)

/** State of the MDR *control channel* (never the audio link). */
enum class ConnectionPhase {
    /** No control channel open. Last-known state may still be shown, marked as not live. */
    IDLE,
    CONNECTING,
    /** Channel open, protocol handshake in progress. */
    INITIALIZING,
    /** Handshake done; reading the device's real state before writing anything. */
    READING_STATE,
    CONNECTED,
    /** The headphones closed our channel while idle: another device/app took control. */
    YIELDED,
    FAILED,
}

sealed interface SessionError {
    val message: String

    data class Channel(val error: ChannelError) : SessionError {
        override val message get() = error.message
    }
    data class Timeout(override val message: String) : SessionError
    data class HandshakeFailed(override val message: String) : SessionError
    data class ProtocolMismatch(override val message: String) : SessionError
    data class UnsupportedModel(override val message: String) : SessionError
    data class Yielded(override val message: String) : SessionError
    data object NoTarget : SessionError {
        override val message = "No headphones selected"
    }
}

data class SessionSnapshot(
    val target: DeviceTarget? = null,
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    /** Device state with queued/in-flight intents applied optimistically. */
    val state: HeadphoneState = HeadphoneState(),
    /** True only when [state] was read from the headphones during the current connection. */
    val stateIsLive: Boolean = false,
    val error: SessionError? = null,
    /** Connected, but some state was not reported within the read timeout. */
    val initialStateTimedOut: Boolean = false,
    val pendingCommands: Int = 0,
) {
    val capabilities: DeviceCapabilities? get() = target?.let { DeviceCatalog.capabilities(it.model) }
}

data class SessionConfig(
    /** Release the control channel after [idleGraceMs] without visible surfaces or commands. */
    val releaseWhenIdle: Boolean = true,
    val idleGraceMs: Long = 20_000,
    val connectTimeoutMs: Long = 8_000,
    val connectRetries: Int = 1,
    val initRetryMs: Long = 1_250,
    val initRetries: Int = 2,
    val ackTimeoutMs: Long = 3_000,
    /** Quiet time after the last state query is acknowledged before writes are allowed. */
    val stateSettleMs: Long = 300,
    val stateTimeoutMs: Long = 6_000,
)
