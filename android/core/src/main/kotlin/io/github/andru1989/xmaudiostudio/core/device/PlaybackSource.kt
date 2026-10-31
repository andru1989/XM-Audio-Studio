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
