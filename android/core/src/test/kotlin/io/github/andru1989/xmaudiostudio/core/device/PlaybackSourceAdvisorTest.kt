package io.github.andru1989.xmaudiostudio.core.device

import io.github.andru1989.xmaudiostudio.core.model.MultipointDevice
import io.github.andru1989.xmaudiostudio.core.model.PlaybackState
import io.github.andru1989.xmaudiostudio.core.model.PlaybackStatus
import io.github.andru1989.xmaudiostudio.core.model.SourceSwitchResult
import io.github.andru1989.xmaudiostudio.core.protocol.MdrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourceAdvisorTest {
    private val phone = MultipointDevice("AA:BB:CC:DD:EE:02", "Galaxy S25 Ultra", 0x01, isPlayback = false)
    private val mac = MultipointDevice("AA:BB:CC:DD:EE:01", "MacBook Pro", 0x02, isPlayback = true)
    private val ipad = MultipointDevice("AA:BB:CC:DD:EE:03", "iPad", 0x00, isPlayback = false)
    private val list = listOf(mac, phone, ipad)
    private val paused = PlaybackStatus(PlaybackState.PAUSED)

    private fun state(status: PlaybackStatus? = paused) =
        HeadphoneState().apply(MdrEvent.DeviceList(list)).let { if (status != null) it.apply(MdrEvent.Playback(status)) else it }

    @Test
    fun findsThisDeviceByAddressThenByUniqueName() {
        assertEquals(mac, Multipoint.thisDevice(list, "aa-bb-cc-dd-ee-01", null))
        assertEquals(phone, Multipoint.thisDevice(list, "02:00:00:00:00:00", "galaxy s25 ultra "))
        assertNull(Multipoint.thisDevice(list, null, "Pixel"))
        assertNull("ambiguous names never match", Multipoint.thisDevice(list + phone.copy(macAddress = "AA:BB:CC:DD:EE:09"), null, "Galaxy S25 Ultra"))
    }

    @Test
    fun controlsElsewhereOnlyWhenThisDevicePlaysAndTheOtherIsIdle() {
        val a = PlaybackSourceAdvisor.advise(state(), phone, localPlayingSinceMs = 0, nowMs = 500, followPlayback = false, lastAutoSwitchMs = null)
        assertTrue(a.controlsElsewhere)
        assertFalse("following is off", a.switchHereNow)
        assertEquals(mac, a.playbackDevice)

        // Not playing here, already the source, the other device is playing, or a call: nothing to say.
        assertFalse(PlaybackSourceAdvisor.advise(state(), phone, null, 500, true, null).controlsElsewhere)
        assertFalse(PlaybackSourceAdvisor.advise(state(), mac, 0, 500, true, null).controlsElsewhere)
        assertFalse(PlaybackSourceAdvisor.advise(state(PlaybackStatus(PlaybackState.PLAYING)), phone, 0, 5_000, true, null).controlsElsewhere)
        assertFalse(PlaybackSourceAdvisor.advise(state(PlaybackStatus(PlaybackState.PAUSED, isCall = true)), phone, 0, 5_000, true, null).controlsElsewhere)
        assertFalse("not connected", PlaybackSourceAdvisor.advise(state(), ipad, 0, 5_000, true, null).controlsElsewhere)
        // Unknown play state (never reported) still warns: it cannot be cutting anything off that we know of.
        assertTrue(PlaybackSourceAdvisor.advise(state(status = null), phone, 0, 500, true, null).controlsElsewhere)
    }

    @Test
    fun followSwitchesOnlyAfterTheSettleTimeAndNotTooOften() {
        val s = state()
        assertFalse(PlaybackSourceAdvisor.advise(s, phone, 10_000, 11_000, true, null).switchHereNow)
        assertTrue(PlaybackSourceAdvisor.advise(s, phone, 10_000, 12_000, true, null).switchHereNow)
        assertFalse(PlaybackSourceAdvisor.advise(s, phone, 10_000, 12_000, true, lastAutoSwitchMs = 0).switchHereNow)
        assertTrue(PlaybackSourceAdvisor.advise(s, phone, 10_000, 15_000, true, lastAutoSwitchMs = 0).switchHereNow)
    }

    @Test
    fun pendingSwitchHoldsAgainstStaleListsAndFailureReverts() {
        val switching = state().applyOptimistic(DeviceCommand.SwitchPlayback(phone.macAddress))
        assertEquals(phone.macAddress, switching.playbackDevice?.macAddress)
        assertFalse("no advice while a switch is pending", PlaybackSourceAdvisor.advise(switching, phone, 0, 5_000, true, null).controlsElsewhere)

        // A list read before the headphones finished switching does not flip the UI back.
        val stale = switching.apply(MdrEvent.DeviceList(list))
        assertEquals(phone.macAddress, stale.playbackDevice?.macAddress)
        assertEquals(mac.macAddress, stale.reportedDevices?.first { it.isPlayback }?.macAddress)

        val refused = stale.apply(MdrEvent.SourceSwitched(SourceSwitchResult.FAILED_A2DP_NOT_CONNECTED, phone.macAddress))
        assertEquals(mac.macAddress, refused.playbackDevice?.macAddress)
        assertEquals(SourceSwitchResult.FAILED_A2DP_NOT_CONNECTED, refused.sourceSwitchFailure)

        val confirmed = stale.apply(MdrEvent.SourceSwitched(SourceSwitchResult.SUCCESS, phone.macAddress))
        assertEquals(phone.macAddress, confirmed.playbackDevice?.macAddress)
        assertNull(confirmed.switchingTo)

        val expired = switching.expireSourceSwitch(phone.macAddress)
        assertEquals(mac.macAddress, expired.playbackDevice?.macAddress)
    }

    @Test
    fun switchToADisconnectedDeviceIsNotShown() {
        val s = state().applyOptimistic(DeviceCommand.SwitchPlayback(ipad.macAddress))
        assertEquals(mac.macAddress, s.playbackDevice?.macAddress)
    }

    @Test
    fun handoverFromAPlayingDeviceIsAnInterruptionAndCanBeRecovered() {
        val phoneHolds = listOf(mac.copy(isPlayback = false), phone.copy(isPlayback = true), ipad)
        val playing = HeadphoneState()
            .apply(MdrEvent.DeviceList(phoneHolds))
            .apply(MdrEvent.Playback(PlaybackStatus(PlaybackState.PLAYING)))
        // The headphones hand the right to the Mac on their own; the phone's music stops.
        val stolen = playing.apply(MdrEvent.DeviceList(list)).apply(MdrEvent.Playback(paused))
        assertEquals(phone.macAddress, stolen.interruptedPlayback?.fromMac)

        val advice = PlaybackSourceAdvisor.advise(stolen, mac, null, 1_000, followPlayback = false, lastAutoSwitchMs = null, interruptedSinceMs = 0)
        assertEquals(phone.macAddress, advice.interrupted?.fromMac)
        assertFalse("following is off", advice.recoverNow)
        assertFalse(PlaybackSourceAdvisor.advise(stolen, mac, null, 1_000, true, null, interruptedSinceMs = 0).recoverNow)
        assertTrue(PlaybackSourceAdvisor.advise(stolen, mac, null, 2_000, true, null, interruptedSinceMs = 0).recoverNow)
        assertFalse(PlaybackSourceAdvisor.advise(stolen, mac, null, 2_000, true, lastAutoSwitchMs = 1_000, interruptedSinceMs = 0).recoverNow)

        // Music plays again (on the Mac, on purpose): nothing to bring back.
        assertNull(stolen.apply(MdrEvent.Playback(PlaybackStatus(PlaybackState.PLAYING))).interruptedPlayback)
        // The right comes back to the phone: done.
        assertNull(stolen.apply(MdrEvent.DeviceList(phoneHolds)).interruptedPlayback)
        // The user switches: never second-guessed.
        assertNull(stolen.applyOptimistic(DeviceCommand.SwitchPlayback(phone.macAddress)).interruptedPlayback)
    }

    @Test
    fun movesThatAreNotInterruptions() {
        val phoneHolds = listOf(mac.copy(isPlayback = false), phone.copy(isPlayback = true), ipad)
        // The phone was paused when the right moved: nothing stopped.
        val idle = HeadphoneState().apply(MdrEvent.DeviceList(phoneHolds)).apply(MdrEvent.Playback(paused))
        assertNull(idle.apply(MdrEvent.DeviceList(list)).interruptedPlayback)
        // The user picked the Mac here: the move it asked for is not an interruption, even confirmed late.
        val chosen = HeadphoneState().apply(MdrEvent.DeviceList(phoneHolds)).apply(MdrEvent.Playback(PlaybackStatus(PlaybackState.PLAYING)))
            .applyOptimistic(DeviceCommand.SwitchPlayback(mac.macAddress))
            .expireSourceSwitch(mac.macAddress)
        assertNull(chosen.apply(MdrEvent.DeviceList(list)).interruptedPlayback)
    }

    @Test
    fun claimTargetIsThisDeviceOnlyWhenItCanTakeTheAudio() {
        assertEquals(phone.macAddress, PlaybackSourceAdvisor.claimTarget(state(), null, "Galaxy S25 Ultra")?.macAddress)
        assertNull("already has it", PlaybackSourceAdvisor.claimTarget(state(), "aa:bb:cc:dd:ee:01", null))
        assertNull("not connected", PlaybackSourceAdvisor.claimTarget(state(), null, "iPad"))
        assertNull("unknown", PlaybackSourceAdvisor.claimTarget(state(), null, "Pixel"))
        assertNull("call", PlaybackSourceAdvisor.claimTarget(state(PlaybackStatus(PlaybackState.PLAYING, isCall = true)), null, "Galaxy S25 Ultra"))
        assertNull("switch pending", PlaybackSourceAdvisor.claimTarget(state().applyOptimistic(DeviceCommand.SwitchPlayback(ipad.macAddress)).copy(switchingTo = ipad.macAddress), null, "Galaxy S25 Ultra"))
        // Newest start wins: the other device playing does not stop the claim.
        assertEquals(phone.macAddress, PlaybackSourceAdvisor.claimTarget(state(PlaybackStatus(PlaybackState.PLAYING)), null, "Galaxy S25 Ultra")?.macAddress)
    }

    @Test
    fun oneClaimPerPlaybackStartAfterTheSettleTime() {
        assertFalse("following off", PlaybackSourceAdvisor.shouldClaim(false, 0, 5_000, null, null))
        assertFalse("not playing", PlaybackSourceAdvisor.shouldClaim(true, null, 5_000, null, null))
        assertFalse("too soon: could be a notification", PlaybackSourceAdvisor.shouldClaim(true, 0, 1_500, null, null))
        assertTrue(PlaybackSourceAdvisor.shouldClaim(true, 0, 2_000, null, null))
        assertFalse("this start was already claimed", PlaybackSourceAdvisor.shouldClaim(true, 0, 9_000, claimedStartMs = 0, lastAutoSwitchMs = 2_000))
        assertFalse("a new start right after the last action", PlaybackSourceAdvisor.shouldClaim(true, 10_000, 12_000, 0, lastAutoSwitchMs = 2_000))
        assertTrue(PlaybackSourceAdvisor.shouldClaim(true, 20_000, 22_000, 0, lastAutoSwitchMs = 2_000))
    }

    @Test
    fun theDeviceLosingTheAudioIsPausedUnlessItIsAlreadyQuiet() {
        val playing = state(PlaybackStatus(PlaybackState.PLAYING))
        assertTrue(PlaybackSourceAdvisor.pauseBeforeSwitch(playing, phone.macAddress))
        assertTrue("unreported state: a pause is harmless", PlaybackSourceAdvisor.pauseBeforeSwitch(state(null), phone.macAddress))
        assertFalse("already paused", PlaybackSourceAdvisor.pauseBeforeSwitch(state(), phone.macAddress))
        assertFalse("stopped", PlaybackSourceAdvisor.pauseBeforeSwitch(state(PlaybackStatus(PlaybackState.STOPPED)), phone.macAddress))
        assertFalse("call", PlaybackSourceAdvisor.pauseBeforeSwitch(state(PlaybackStatus(PlaybackState.PLAYING, isCall = true)), phone.macAddress))
        assertFalse("controls disabled", PlaybackSourceAdvisor.pauseBeforeSwitch(state(PlaybackStatus(PlaybackState.PLAYING, controlsAvailable = false)), phone.macAddress))
        assertFalse("already the holder", PlaybackSourceAdvisor.pauseBeforeSwitch(playing, mac.macAddress))
        assertFalse("no list", PlaybackSourceAdvisor.pauseBeforeSwitch(HeadphoneState(), phone.macAddress))
    }
}
