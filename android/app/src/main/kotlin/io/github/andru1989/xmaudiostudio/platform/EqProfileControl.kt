package io.github.andru1989.xmaudiostudio.platform

import io.github.andru1989.xmaudiostudio.core.device.HeadphoneDevice
import io.github.andru1989.xmaudiostudio.core.presets.EqProfile
import io.github.andru1989.xmaudiostudio.core.session.SessionError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Equalizer-profile actions for the home-screen widget: apply a profile, or step to the next /
 * previous one. Same channel policy as [NoiseControl]: a tap may open the channel, and nothing
 * is written before the real state has been read (the active profile and the listening mode
 * both come from that read).
 *
 * Must be called on the session's thread (main), like the session itself.
 */
class EqProfileControl(
    private val device: () -> HeadphoneDevice,
    /** Restores the saved target if needed and says whether acting is allowed. */
    private val prepare: () -> Availability,
    /** The profiles of the selected model (built-in + user). */
    private val profiles: (modelId: String) -> List<EqProfile>,
    private val lastAppliedId: () -> String?,
    private val rememberApplied: (String) -> Unit,
    private val liveTimeoutMs: Long = NoiseControl.LIVE_TIMEOUT_MS,
    private val deliveryTimeoutMs: Long = NoiseControl.DELIVERY_TIMEOUT_MS,
    private val ackGraceMs: Long = NoiseControl.ACK_GRACE_MS,
) {
    sealed interface Request {
        data class Apply(val profileId: String) : Request
        /** +1 next, -1 previous, in the widget's order. */
        data class Step(val direction: Int) : Request
    }

    sealed interface Outcome {
        data class Sent(val profile: EqProfile) : Outcome
        data class AlreadyActive(val profile: EqProfile) : Outcome
        data class Unavailable(val availability: Availability) : Outcome
        data class NotConnected(val error: SessionError?) : Outcome
        /** Background Music or Cinema is on: the firmware ignores EQ changes, so nothing is written. */
        data object BlockedByListeningMode : Outcome
        /** The profile no longer exists (deleted in the app) or the model has none. */
        data object NotFound : Outcome
    }

    /** Serialises taps so two quick "next" presses move two profiles, not one twice. */
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
        val target = snapshot.target ?: return Outcome.Unavailable(Availability.NO_DEVICE)
        val caps = snapshot.capabilities ?: return Outcome.Unavailable(Availability.UNSUPPORTED_MODEL)
        if (EqProfileLogic.listeningModeBlocksEq(snapshot.state.listeningMode)) return Outcome.BlockedByListeningMode

        val ordered = EqProfileLogic.ordered(profiles(target.model.id))
        // Includes intents still in flight, so a second "next" builds on the first one.
        val active = EqProfileLogic.activeProfile(ordered, snapshot.state.equalizer, caps, lastAppliedId())
        val chosen = when (request) {
            is Request.Apply -> ordered.firstOrNull { it.id == request.profileId }
            is Request.Step -> EqProfileLogic.neighbour(ordered, active?.id ?: lastAppliedId(), request.direction)
        } ?: return Outcome.NotFound
        if (chosen.id == active?.id) return Outcome.AlreadyActive(chosen)

        val commands = EqProfileLogic.commands(chosen, caps)
        if (commands.isEmpty()) return Outcome.NotFound
        rememberApplied(chosen.id)
        commands.forEach(session::execute)
        return Outcome.Sent(chosen)
    }
}
