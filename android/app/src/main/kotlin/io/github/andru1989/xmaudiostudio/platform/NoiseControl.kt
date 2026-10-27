package io.github.andru1989.xmaudiostudio.platform

import io.github.andru1989.xmaudiostudio.core.device.HeadphoneDevice
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.session.SessionError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Noise-control actions for surfaces outside the app (Quick Settings tile, home-screen widget).
 *
 * Both go through the process-wide session, so they follow the same channel policy as the app:
 * the channel is acquired on demand (a tap is user activity, so it may connect or take control
 * back from a "yielded" state) and released 20 s after the last activity.
 *
 * Rule 3 applies before deciding anything: when the state is not live, the session is asked to
 * connect and the decision waits for the real state. That matters twice: the tile's next mode
 * depends on the current one, and the write carries companion values (focus on voice, ambient
 * level) that must come from the headphones, not from a stale record.
 *
 * Must be called on the session's thread (main), like the session itself.
 */
class NoiseControl(
    private val device: () -> HeadphoneDevice,
    /** Restores the saved target if needed and says whether acting is allowed. */
    private val prepare: () -> Availability,
    private val liveTimeoutMs: Long = LIVE_TIMEOUT_MS,
    private val deliveryTimeoutMs: Long = DELIVERY_TIMEOUT_MS,
    private val ackGraceMs: Long = ACK_GRACE_MS,
) {
    sealed interface Request {
        /** Quick Settings tile: NC → Ambient → Off → NC. */
        data object Cycle : Request
        /** Widget buttons. */
        data class Set(val mode: AmbientSoundMode) : Request
    }

    sealed interface Outcome {
        data class Sent(val mode: AmbientSoundMode) : Outcome
        /** Explicit mode already active: nothing written. */
        data class AlreadyInMode(val mode: AmbientSoundMode) : Outcome
        data class Unavailable(val availability: Availability) : Outcome
        /** The state never became live (failed, yielded again or timed out): nothing written. */
        data class NotConnected(val error: SessionError?) : Outcome
        /** Connected, but the headphones did not report their noise mode: nothing written. */
        data object StateUnknown : Outcome
    }

    /** Serialises decisions so rapid taps step NC → Ambient → Off instead of racing on one read. */
    private val decisions = Mutex()

    suspend fun perform(request: Request): Outcome {
        val outcome = decisions.withLock { decideAndSend(request) }
        if (outcome is Outcome.Sent) awaitDelivery(device(), deliveryTimeoutMs, ackGraceMs)
        return outcome
    }

    private suspend fun decideAndSend(request: Request): Outcome {
        val availability = prepare()
        if (availability != Availability.READY) return Outcome.Unavailable(availability)
        val session = device()
        val snapshot = awaitLiveSnapshot(session, liveTimeoutMs) ?: return Outcome.NotConnected(session.snapshot.value.error)
        // Includes intents still in flight, so a second tap builds on the first one.
        val current = snapshot.state.ambientSound ?: return Outcome.StateUnknown
        val mode = when (request) {
            Request.Cycle -> NoiseControlLogic.nextMode(current.mode)
            is Request.Set -> request.mode
        }
        if (mode == current.mode) return Outcome.AlreadyInMode(mode)
        session.execute(NoiseControlLogic.command(mode, current))
        return Outcome.Sent(mode)
    }

    companion object {
        /** Covers the session's own budget: 2 × 8 s connect, handshake retries and a 6 s state read. */
        const val LIVE_TIMEOUT_MS = 30_000L
        const val DELIVERY_TIMEOUT_MS = 5_000L
        const val ACK_GRACE_MS = 750L
    }
}
