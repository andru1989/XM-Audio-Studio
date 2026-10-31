package io.github.andru1989.xmaudiostudio.core.session

import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.model.PlaybackAction
import io.github.andru1989.xmaudiostudio.core.model.PlaybackState
import io.github.andru1989.xmaudiostudio.core.model.SourceSwitchResult
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType
import io.github.andru1989.xmaudiostudio.core.protocol.u
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the audio is: list, play state, track, switching, and how fast a change shows up. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSourceSessionTest {

    private val xm6 = DeviceTarget("AA:BB:CC:DD:EE:FF", "WH-1000XM6", HeadphoneModel.WH_1000XM6)
    private val mac = "AA:BB:CC:DD:EE:01"
    private val pixel = "AA:BB:CC:DD:EE:02"

    private class Rig(scope: CoroutineScope, configure: SimulatedXm6Channel.() -> Unit) {
        val device = SimulatedXm6Channel(scope).apply(configure)
        val session = HeadphoneSession(device, scope)
        val state get() = session.snapshot.value.state
        fun listReads() = device.received.count { it.type == MessageType.COMMAND_2 && it.payload.u(0) == 0x36 }
    }

    private fun TestScope.rig(configure: SimulatedXm6Channel.() -> Unit = {}) = Rig(backgroundScope, configure)

    private fun TestScope.settle(ms: Long = 2_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.visible(configure: SimulatedXm6Channel.() -> Unit = {}) = rig(configure).also {
        it.session.selectTarget(xm6)
        it.session.setSurfaceVisible("main", true)
        settle()
    }

    @Test
    fun connectReadsSourcePlayStateAndTrack() = runTest {
        val r = visible()
        assertEquals(mac, r.state.playbackDevice?.macAddress)
        assertEquals(PlaybackState.PLAYING, r.state.playbackStatus?.state)
        assertEquals("Amanecer en la ciudad", r.state.nowPlaying?.title)
        assertEquals("XM Audio Studio", r.state.nowPlaying?.artist)
    }

    @Test
    fun switchIsShownAtOnceAndConfirmedByTheHeadphones() = runTest {
        val r = visible()
        r.session.execute(DeviceCommand.SwitchPlayback(pixel))
        assertEquals("optimistic", pixel, r.state.playbackDevice?.macAddress)
        assertEquals(pixel, r.state.switchingTo)
        settle()
        assertEquals(1, r.device.playbackIndex)
        assertEquals(pixel, r.state.playbackDevice?.macAddress)
        assertNull("confirmed by 3d 01", r.state.switchingTo)
        assertNull(r.state.sourceSwitchFailure)
    }

    @Test
    fun refusedSwitchRevertsAndSaysWhy() = runTest {
        val r = visible { sourceSwitchResult = 0x02 }
        r.session.execute(DeviceCommand.SwitchPlayback(pixel))
        settle()
        assertEquals(0, r.device.playbackIndex)
        assertEquals(mac, r.state.playbackDevice?.macAddress)
        assertEquals(SourceSwitchResult.FAILED_CALLING, r.state.sourceSwitchFailure)
        assertNull(r.state.switchingTo)
    }

    @Test
    fun unansweredSwitchExpiresToTheReportedSource() = runTest {
        val r = visible { answersSourceSwitch = false }
        r.session.execute(DeviceCommand.SwitchPlayback(pixel))
        settle(1_000)
        assertEquals("still pending", pixel, r.state.playbackDevice?.macAddress)
        settle(5_000)
        assertNull(r.state.switchingTo)
        assertEquals(mac, r.state.playbackDevice?.macAddress)
    }

    @Test
    fun notifiedTakeoverShowsUpImmediately() = runTest {
        val r = visible()
        r.device.simulateSourceTakeover(1, notify = true)
        settle(100)
        assertEquals(pixel, r.state.playbackDevice?.macAddress)
    }

    @Test
    fun silentTakeoverIsFoundByTheWatchWhileVisible() = runTest {
        val r = visible()
        r.device.simulateSourceTakeover(1, notify = false)
        settle(1_000)
        assertEquals("not polled yet", mac, r.state.playbackDevice?.macAddress)
        settle(3_000)
        assertEquals(pixel, r.state.playbackDevice?.macAddress)
    }

    @Test
    fun watchStopsWhenNothingIsVisibleAndNeverHoldsTheChannel() = runTest {
        val r = visible()
        val reads = r.listReads()
        r.session.setSurfaceVisible("main", false)
        settle(10_000)
        assertEquals("no polling without a visible surface", reads, r.listReads())
        settle(11_000)
        assertEquals(ConnectionPhase.IDLE, r.session.snapshot.value.phase)
    }

    @Test
    fun refreshWhileReleasedDoesNotOpenTheChannel() = runTest {
        val r = rig()
        r.session.selectTarget(xm6)
        r.session.refreshPlaybackSource()
        settle()
        assertEquals(0, r.device.openCount)
    }

    @Test
    fun playbackControlsAreRelayedAndReconciled() = runTest {
        val r = visible()
        r.session.execute(DeviceCommand.ControlPlayback(PlaybackAction.PAUSE))
        assertEquals("optimistic", PlaybackState.PAUSED, r.state.playbackStatus?.state)
        settle()
        assertEquals(0x02, r.device.playbackState)
        assertEquals(PlaybackState.PAUSED, r.state.playbackStatus?.state)

        r.session.execute(DeviceCommand.ControlPlayback(PlaybackAction.NEXT))
        settle()
        assertEquals("Ruido blanco", r.state.nowPlaying?.title)
        val writes = r.device.received.filter { it.payload.size >= 2 && it.payload.u(0) == 0xa4 }
        assertTrue(writes.isNotEmpty())
    }

    @Test
    fun switchWithResumeSendsPlayOnceConfirmed() = runTest {
        val r = visible { playbackState = 0x02 }
        r.session.execute(DeviceCommand.SwitchPlayback(pixel, resumePlayback = true))
        settle()
        assertEquals(1, r.device.playbackIndex)
        assertEquals(0x01, r.device.playbackState)
        assertEquals(PlaybackState.PLAYING, r.state.playbackStatus?.state)
    }

    @Test
    fun refusedSwitchNeverResumes() = runTest {
        val r = visible { playbackState = 0x02; sourceSwitchResult = 0x01 }
        r.session.execute(DeviceCommand.SwitchPlayback(pixel, resumePlayback = true))
        settle()
        assertEquals(0x02, r.device.playbackState)
        assertTrue(r.device.received.none { it.payload.size >= 4 && it.payload.u(0) == 0xa4 })
    }

    @Test
    fun handoverWhilePlayingIsReportedAsAnInterruption() = runTest {
        val r = visible()
        // The phone takes the right on its own and the music on the Mac stops.
        r.device.playbackState = 0x02
        r.device.simulateSourceTakeover(1, notify = true)
        settle(500)
        assertEquals(mac, r.state.interruptedPlayback?.fromMac)
        assertEquals(pixel, r.state.interruptedPlayback?.toMac)
    }
}
