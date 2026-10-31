package io.github.andru1989.xmaudiostudio.core.device

import io.github.andru1989.xmaudiostudio.core.model.InterruptedPlayback
import io.github.andru1989.xmaudiostudio.core.model.MultipointDevice
import io.github.andru1989.xmaudiostudio.core.model.PlaybackState

/**
 * Where the audio is, from the app's point of view. Same rules as
 * macos/Sources/SonyHeadphonesKit/Multipoint/PlaybackSource.swift (docs/decisions/ADR-0010).
 */
object Multipoint {
    /** `AA:BB:..`, `aa-bb-..` and `aabb..` compare equal; Android's privacy placeholder never matches. */
    fun normalizeAddress(address: String?): String? =
        address?.filter { it.isLetterOrDigit() }?.uppercase()?.takeIf { it.length == 12 && it != "020000000000" }

    /**
     * The list entry for the device this app runs on: by Bluetooth address when the platform gives it
     * (macOS), else by a name that matches exactly one entry (Android hides its own address).
     */
    fun thisDevice(devices: List<MultipointDevice>?, localAddress: String?, localName: String?): MultipointDevice? {
        val list = devices ?: return null
        normalizeAddress(localAddress)?.let { address ->
            list.firstOrNull { normalizeAddress(it.macAddress) == address }?.let { return it }
        }
        val name = localName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return list.singleOrNull { it.name.trim().equals(name, ignoreCase = true) }
    }
}

data class SourceAdvice(
    val thisDevice: MultipointDevice?,
    /** The device the headphones' buttons act on (playback right). */
    val playbackDevice: MultipointDevice?,
    /**
     * This device is sending audio to the headphones, but their buttons (and pause) act on another
     * device that is not playing: the "I can't pause it" situation.
     */
    val controlsElsewhere: Boolean,
    /** [controlsElsewhere] has lasted [PlaybackSourceAdvisor.SETTLE_MS], following is on and allowed now. */
    val switchHereNow: Boolean,
    /**
     * Music on [InterruptedPlayback.fromMac] stopped because the headphones gave the playback right to
     * another device. Offer "back there and resume"; null when there is nothing to bring back.
     */
    val interrupted: InterruptedPlayback? = null,
    /** [interrupted] has lasted [PlaybackSourceAdvisor.SETTLE_MS], following is on and allowed now. */
    val recoverNow: Boolean = false,
)

object PlaybackSourceAdvisor {
    /**
     * The device a [DeviceCommand.ClaimPlayback] moves the audio to, judged on fresh state: this
     * device, when it is connected, does not already hold the playback right, no switch is pending
     * and no call is in progress. Null means "write nothing".
     */
    fun claimTarget(state: HeadphoneState, localAddress: String?, localName: String?): MultipointDevice? {
        val here = Multipoint.thisDevice(state.reportedDevices ?: state.devices, localAddress, localName) ?: return null
        if (!here.isConnected || here.isPlayback || state.switchingTo != null || state.playbackStatus?.isCall == true) return null
        return here
    }

    /**
     * Whether moving the audio to [targetMac] should first pause the device that has it now, so only
     * one keeps playing (ADR-0012). The pause (`a4 01 00 01`) goes through the headphones to the
     * current holder of the playback right, so it must be sent before the switch. Not sent when the
     * holder is the target, already paused or stopped, in a call, or reports its controls disabled;
     * an unreported state still gets it (a pause to a player that is not playing does nothing).
     */
    fun pauseBeforeSwitch(state: HeadphoneState, targetMac: String): Boolean {
        val holder = (state.reportedDevices ?: state.devices)?.firstOrNull { it.isPlayback } ?: return false
        val status = state.playbackStatus
        return holder.macAddress != targetMac && status?.isCall != true && status?.controlsAvailable != false &&
            status?.state != PlaybackState.PAUSED && status?.state != PlaybackState.STOPPED
    }

    /**
     * Whether this device should claim the audio now: following is on, it has been playing for
     * [SETTLE_MS] (a notification sound never counts), this playback start was not claimed yet, and
     * the last automatic action was at least [MIN_INTERVAL_MS] ago. One claim per start, so two
     * devices that both keep playing never ping-pong: the newest start wins.
     */
    fun shouldClaim(
        followPlayback: Boolean,
        localPlayingSinceMs: Long?,
        nowMs: Long,
        claimedStartMs: Long?,
        lastAutoSwitchMs: Long?,
    ): Boolean = followPlayback && localPlayingSinceMs != null && nowMs - localPlayingSinceMs >= SETTLE_MS &&
        claimedStartMs != localPlayingSinceMs &&
        (lastAutoSwitchMs == null || nowMs - lastAutoSwitchMs >= MIN_INTERVAL_MS)

    /** Local audio must last this long first: a notification sound never moves the controls. */
    const val SETTLE_MS = 2_000L

    /** At most one automatic switch in this window, so two devices can never ping-pong. */
    const val MIN_INTERVAL_MS = 15_000L

    /**
     * Never suggests taking the controls from a device that is playing, during a call, or while a
     * switch is already pending.
     *
     * @param localPlayingSinceMs when this device started sending audio to the headphones; null if it is not.
     * @param interruptedSinceMs when [HeadphoneState.interruptedPlayback] was first seen; null if it is not set.
     * @param lastAutoSwitchMs the last automatic switch of either kind, so they can never ping-pong.
     */
    fun advise(
        state: HeadphoneState,
        thisDevice: MultipointDevice?,
        localPlayingSinceMs: Long?,
        nowMs: Long,
        followPlayback: Boolean,
        lastAutoSwitchMs: Long?,
        interruptedSinceMs: Long? = null,
    ): SourceAdvice {
        val active = state.playbackDevice
        val status = state.playbackStatus
        val elsewhere = thisDevice != null && thisDevice.isConnected && localPlayingSinceMs != null &&
            state.switchingTo == null && active != null && active.macAddress != thisDevice.macAddress &&
            status?.state != PlaybackState.PLAYING && status?.isCall != true
        val allowedNow = lastAutoSwitchMs == null || nowMs - lastAutoSwitchMs >= MIN_INTERVAL_MS
        val switchNow = elsewhere && followPlayback && localPlayingSinceMs != null &&
            nowMs - localPlayingSinceMs >= SETTLE_MS && allowedNow

        val interrupted = state.interruptedPlayback?.takeIf { i ->
            state.switchingTo == null && status?.state != PlaybackState.PLAYING && status?.isCall != true &&
                active?.macAddress != i.fromMac && state.devices?.any { it.macAddress == i.fromMac && it.isConnected } == true
        }
        val recoverNow = interrupted != null && followPlayback && interruptedSinceMs != null &&
            nowMs - interruptedSinceMs >= SETTLE_MS && allowedNow && !switchNow
        return SourceAdvice(thisDevice, active, elsewhere, switchNow, interrupted, recoverNow)
    }
}
