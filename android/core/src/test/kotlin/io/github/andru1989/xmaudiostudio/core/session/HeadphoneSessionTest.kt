package io.github.andru1989.xmaudiostudio.core.session

import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType
import io.github.andru1989.xmaudiostudio.core.protocol.bytes
import io.github.andru1989.xmaudiostudio.core.protocol.toHex
import io.github.andru1989.xmaudiostudio.core.protocol.u
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeadphoneSessionTest {

    private val xm6 = DeviceTarget("AA:BB:CC:DD:EE:FF", "WH-1000XM6", HeadphoneModel.WH_1000XM6)

    private class Rig(scope: CoroutineScope, configure: SimulatedXm6Channel.() -> Unit = {}) {
        val device = SimulatedXm6Channel(scope).apply(configure)
        val session = HeadphoneSession(device, scope)
        val snapshot get() = session.snapshot.value
    }

    private fun TestScope.rig(configure: SimulatedXm6Channel.() -> Unit = {}) = Rig(backgroundScope, configure)

    private fun TestScope.settle(ms: Long = 2_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun visibleSurfaceConnectsAndReadsRealState() = runTest {
        val r = rig()
        r.session.selectTarget(xm6)
        r.session.setSurfaceVisible("main", true)
        settle()

        val s = r.snapshot
        assertEquals(ConnectionPhase.CONNECTED, s.phase)
        assertTrue(s.stateIsLive)
        // The 0x17 placeholder (all zeros) must not override the real 0x19 report.
        assertEquals(AmbientSoundMode.NOISE_CANCELLING, s.state.ambientSound?.mode)
        assertEquals(12, s.state.ambientSound?.level)
        assertEquals(72, s.state.battery?.level)
        assertEquals(listOf(1, 1, 0, 0, 0, 0, 0, 1, 1, 0), s.state.equalizer?.bands)
        assertEquals(ListeningMode.STANDARD, s.state.listeningMode)
        assertEquals(2, s.state.devices?.size)
        assertEquals(1, r.device.openCount)
    }

    @Test
    fun commandWhileReleasedReconnectsReadsStateThenWrites() = runTest {
        val r = rig()
        r.session.selectTarget(xm6)
        r.session.execute(DeviceCommand.SetEqualizerBands(listOf(3, 2, 1, 0, -1, -2, -3, 0, 0, 0)))
        settle()

        assertEquals(ConnectionPhase.CONNECTED, r.snapshot.phase)
        val payloads = r.device.received.filter { it.type != MessageType.ACK }.map { it.payload }
        val writeIndex = payloads.indexOfFirst { it.u(0) == 0x58 }
        val lastRead = payloads.indexOfLast { it.u(0) in setOf(0x04, 0x22, 0x26, 0x36, 0x50, 0x56, 0x66, 0xe6, 0xf6, 0xfa) }
        assertTrue("state must be read before writing ($lastRead < $writeIndex)", lastRead in 0 until writeIndex)
        assertEquals("58 04 a0 0a 09 08 07 06 05 04 03 06 06 06", payloads[writeIndex].toHex())
        assertEquals(listOf(3, 2, 1, 0, -1, -2, -3, 0, 0, 0), r.device.eqBands)
    }

    @Test
    fun pendingIntentIsEncodedAgainstFreshState() = runTest {
        // The UI never saw the room size; the device has "large". Switching to BGM must keep it.
        val r = rig { bgmRoom = BgmRoomSize.LARGE.code }
        r.session.selectTarget(xm6)
        r.session.execute(DeviceCommand.SetListeningMode(ListeningMode.BACKGROUND_MUSIC))
        settle()
        val bgmWrite = r.device.received.map { it.payload }.first { it.size >= 2 && it.u(0) == 0xe8 && it.u(1) == 0x09 }
        assertEquals("e8 09 00 02", bgmWrite.toHex())
        assertEquals(ListeningMode.BACKGROUND_MUSIC, r.snapshot.state.listeningMode)
    }

    @Test
    fun releasesChannelAfterIdleGraceAndKeepsLastKnownState() = runTest {
        val r = rig()
        r.session.selectTarget(xm6)
        r.session.execute(DeviceCommand.SetPauseWhenTakenOff(false))
        settle()
        assertEquals(ConnectionPhase.CONNECTED, r.snapshot.phase)

        advanceTimeBy(17_000); runCurrent()
        assertTrue("still within the 20 s grace period", r.device.isOpen)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(ConnectionPhase.IDLE, r.snapshot.phase)
        assertFalse(r.device.isOpen)
        assertFalse(r.snapshot.stateIsLive)
        assertEquals(false, r.snapshot.state.pauseWhenTakenOff)
        assertNotNull(r.snapshot.state.ambientSound)
    }

    @Test
    fun visibleSurfaceHoldsChannelUntilHidden() = runTest {
        val r = rig()
        r.session.selectTarget(xm6)
        r.session.setSurfaceVisible("main", true)
        settle(60_000)
        assertEquals(ConnectionPhase.CONNECTED, r.snapshot.phase)

        r.session.setSurfaceVisible("main", false)
        settle(21_000)
        assertEquals(ConnectionPhase.IDLE, r.snapshot.phase)
        assertFalse(r.device.isOpen)
    }

    @Test
    fun remoteCloseWhileIdleMeansYieldedAndNoReconnect() = runTest {
        val r = rig()
        r.session.selectTarget(xm6)
        r.session.setSurfaceVisible("main", true)
        settle()
        r.device.simulateRemoteTakeover()
        settle(60_000)

        assertEquals(ConnectionPhase.YIELDED, r.snapshot.phase)
        assertTrue(r.snapshot.error is SessionError.Yielded)
        assertEquals("no aggressive reconnect while another device has control", 1, r.device.openCount)

        // Fresh user activity takes control back, reading state first.
        r.session.execute(DeviceCommand.SetAmbientSound(AmbientSoundMode.AMBIENT_SOUND, false, 10))
        settle()
        assertEquals(ConnectionPhase.CONNECTED, r.snapshot.phase)
        assertEquals(2, r.device.openCount)
        assertTrue(r.device.ambient)
        assertEquals(10, r.device.ambientLevel)
    }

    @Test
    fun physicalButtonNotificationUpdatesState() = runTest {
        val r = rig()
        r.session.selectTarget(xm6)
        r.session.setSurfaceVisible("main", true)
        settle()
        r.device.simulateNoiseControlButton()
        settle(500)
        assertEquals(AmbientSoundMode.AMBIENT_SOUND, r.snapshot.state.ambientSound?.mode)
    }

    @Test
    fun rapidEqualizerChangesAreCoalesced() = runTest {
        val r = rig()
        r.session.selectTarget(xm6)
        r.session.setSurfaceVisible("main", true)
        settle()
        for (i in -6..6) {
            r.session.execute(DeviceCommand.SetEqualizerBands(List(10) { i }))
            advanceTimeBy(5); runCurrent()
        }
        settle()
        val writes = r.device.received.count { it.type == MessageType.COMMAND_1 && it.payload.u(0) == 0x58 }
        assertTrue("expected coalescing, got $writes writes for 13 changes", writes < 13)
        assertEquals(List(10) { 6 }, r.device.eqBands)
        assertEquals(List(10) { 6 }, r.snapshot.state.equalizer?.bands)
    }

    @Test
    fun busyChannelFailsWithoutRetryLoopAndRevertsOptimisticState() = runTest {
        val r = rig { refuseConnections = true }
        r.session.selectTarget(xm6)
        r.session.execute(DeviceCommand.SetPauseWhenTakenOff(false))
        assertEquals(false, r.snapshot.state.pauseWhenTakenOff) // optimistic while connecting
        settle(30_000)
        assertEquals(ConnectionPhase.FAILED, r.snapshot.phase)
        assertTrue(r.snapshot.error is SessionError.Channel)
        assertEquals(0, r.snapshot.pendingCommands)
        assertEquals(null, r.snapshot.state.pauseWhenTakenOff)
        assertEquals(1, r.device.openCount)
    }

    @Test
    fun silentChannelTimesOutAfterOneRetry() = runTest {
        val silent = object : ControlChannel {
            var opens = 0
            override fun open(address: String, listener: ControlChannel.Listener) { opens++ }
            override fun write(bytes: ByteArray) = Unit
            override fun close() = Unit
        }
        val session = HeadphoneSession(silent, backgroundScope)
        session.selectTarget(xm6)
        session.connect()
        advanceTimeBy(8_100); runCurrent()
        assertEquals(ConnectionPhase.CONNECTING, session.snapshot.value.phase)
        advanceTimeBy(8_100); runCurrent()
        assertEquals(ConnectionPhase.FAILED, session.snapshot.value.phase)
        assertTrue(session.snapshot.value.error is SessionError.Timeout)
        assertEquals(2, silent.opens)
    }

    @Test
    fun modelsWithoutAdapterAreNeverContacted() = runTest {
        val r = rig()
        r.session.selectTarget(DeviceTarget("11:22:33:44:55:66", "WH-1000XM4", HeadphoneModel.WH_1000XM4))
        r.session.setSurfaceVisible("main", true)
        settle()
        assertEquals(ConnectionPhase.FAILED, r.snapshot.phase)
        assertTrue(r.snapshot.error is SessionError.UnsupportedModel)
        assertEquals(0, r.device.openCount)
    }

    @Test
    fun unexpectedProtocolVersionAbortsBeforeAnyWrite() = runTest {
        val r = rig { initReply = bytes(0x01, 0x00, 0x40, 0x10) } // a 4-byte (v1) reply
        r.session.selectTarget(xm6)
        r.session.execute(DeviceCommand.SetPauseWhenTakenOff(true))
        settle()
        assertEquals(ConnectionPhase.FAILED, r.snapshot.phase)
        assertTrue(r.snapshot.error is SessionError.ProtocolMismatch)
        val commands = r.device.received.filter { it.type != MessageType.ACK }
        assertEquals("only the init request was sent", listOf("00 00"), commands.map { it.payload.toHex() })
    }

    @Test
    fun explicitDisconnectReleasesImmediately() = runTest {
        val r = rig()
        r.session.selectTarget(xm6)
        r.session.setSurfaceVisible("main", true)
        settle()
        r.session.disconnect()
        runCurrent()
        assertEquals(ConnectionPhase.IDLE, r.snapshot.phase)
        assertFalse(r.device.isOpen)
        settle(60_000)
        assertEquals("a still-visible surface does not reacquire by itself", 1, r.device.openCount)
    }

    @Test
    fun readsModelNameFirmwareAndEqCapabilityWhenReported() = runTest {
        val r = rig()
        r.session.selectTarget(xm6)
        r.session.setSurfaceVisible("main", true)
        settle()
        // The simulator does not answer identity queries: the state stays unknown rather than invented.
        assertEquals(null, r.snapshot.state.deviceInfo.modelName)
        assertTrue(r.device.received.any { it.payload.toHex() == "04 01" })
        assertTrue(r.device.received.any { it.payload.toHex() == "04 02" })
        assertTrue(r.device.received.any { it.payload.toHex() == "50 04 01" })
    }
}
