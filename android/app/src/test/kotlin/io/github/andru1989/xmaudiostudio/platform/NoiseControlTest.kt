package io.github.andru1989.xmaudiostudio.platform

import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType
import io.github.andru1989.xmaudiostudio.core.protocol.toHex
import io.github.andru1989.xmaudiostudio.core.protocol.u
import io.github.andru1989.xmaudiostudio.core.session.ConnectionPhase
import io.github.andru1989.xmaudiostudio.core.session.DeviceTarget
import io.github.andru1989.xmaudiostudio.core.session.HeadphoneSession
import io.github.andru1989.xmaudiostudio.core.session.SimulatedXm6Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tile/widget actions against the real session and the byte-level XM6 simulator. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoiseControlTest {

    private class Rig(scope: CoroutineScope) {
        val target = DeviceTarget("AA:BB:CC:DD:EE:FF", "WH-1000XM6", HeadphoneModel.WH_1000XM6)
        val device = SimulatedXm6Channel(scope) // starts in noise cancelling, ambient level 12
        val session = HeadphoneSession(device, scope)
        var availability = Availability.READY
        val control = NoiseControl(device = { session }, prepare = {
            session.selectTarget(target)
            availability
        })

        fun payloads() = device.received.filter { it.type != MessageType.ACK }.map { it.payload }
        fun ambientWrites() = payloads().filter { it.u(0) == 0x68 }.map { it.toHex() }
    }

    private fun TestScope.rig() = Rig(backgroundScope)

    @Test
    fun tapWithReleasedChannelReadsRealStateThenWrites() = runTest {
        val r = rig()
        assertFalse(r.device.isOpen)

        val outcome = r.control.perform(NoiseControl.Request.Cycle)

        assertEquals(NoiseControl.Outcome.Sent(AmbientSoundMode.AMBIENT_SOUND), outcome)
        assertTrue(r.device.ancOn && r.device.ambient)
        val payloads = r.payloads()
        val write = payloads.indexOfFirst { it.u(0) == 0x68 }
        val lastAmbientRead = payloads.indexOfLast { it.u(0) == 0x66 }
        assertTrue("state must be read before writing", lastAmbientRead in 0 until write)
        // Focus on voice (off) and level 12 come from the headphones, not from defaults.
        assertEquals(listOf("68 17 01 01 01 00 0c"), r.ambientWrites())
    }

    @Test
    fun consecutiveTapsCycleThroughAllModes() = runTest {
        val r = rig()
        assertEquals(NoiseControl.Outcome.Sent(AmbientSoundMode.AMBIENT_SOUND), r.control.perform(NoiseControl.Request.Cycle))
        assertEquals(NoiseControl.Outcome.Sent(AmbientSoundMode.OFF), r.control.perform(NoiseControl.Request.Cycle))
        assertFalse(r.device.ancOn)
        assertEquals(NoiseControl.Outcome.Sent(AmbientSoundMode.NOISE_CANCELLING), r.control.perform(NoiseControl.Request.Cycle))
        assertTrue(r.device.ancOn && !r.device.ambient)
        assertEquals(1, r.device.openCount)
    }

    @Test
    fun rapidTapsWhileConnectingAreNotLost() = runTest {
        val r = rig()
        val first = async { r.control.perform(NoiseControl.Request.Cycle) }
        val second = async { r.control.perform(NoiseControl.Request.Cycle) }

        assertEquals(NoiseControl.Outcome.Sent(AmbientSoundMode.AMBIENT_SOUND), first.await())
        assertEquals(NoiseControl.Outcome.Sent(AmbientSoundMode.OFF), second.await())
        assertFalse(r.device.ancOn)
    }

    @Test
    fun widgetButtonForTheCurrentModeWritesNothing() = runTest {
        val r = rig()
        val outcome = r.control.perform(NoiseControl.Request.Set(AmbientSoundMode.NOISE_CANCELLING))
        assertEquals(NoiseControl.Outcome.AlreadyInMode(AmbientSoundMode.NOISE_CANCELLING), outcome)
        assertTrue(r.ambientWrites().isEmpty())
    }

    @Test
    fun widgetButtonSetsTheMode() = runTest {
        val r = rig()
        assertEquals(NoiseControl.Outcome.Sent(AmbientSoundMode.OFF), r.control.perform(NoiseControl.Request.Set(AmbientSoundMode.OFF)))
        assertFalse(r.device.ancOn)
    }

    @Test
    fun unavailableSendsNothing() = runTest {
        val r = rig()
        r.availability = Availability.PERMISSION_MISSING
        assertEquals(NoiseControl.Outcome.Unavailable(Availability.PERMISSION_MISSING), r.control.perform(NoiseControl.Request.Cycle))
        assertEquals(0, r.device.openCount)
    }

    @Test
    fun failedConnectionWritesNothing() = runTest {
        val r = rig()
        r.device.refuseConnections = true
        val outcome = r.control.perform(NoiseControl.Request.Cycle)
        assertTrue(outcome is NoiseControl.Outcome.NotConnected)
        assertEquals(ConnectionPhase.FAILED, r.session.snapshot.value.phase)
        assertTrue(r.ambientWrites().isEmpty())
    }

    @Test
    fun tapTakesControlBackAfterYielding() = runTest {
        val r = rig()
        r.control.perform(NoiseControl.Request.Cycle)
        advanceTimeBy(5_000)
        r.device.simulateRemoteTakeover()
        runCurrent()
        assertEquals(ConnectionPhase.YIELDED, r.session.snapshot.value.phase)

        // A tap is user activity: reconnect, re-read (another device may have changed the mode), write.
        r.device.ancOn = false
        val outcome = r.control.perform(NoiseControl.Request.Cycle)
        assertEquals(NoiseControl.Outcome.Sent(AmbientSoundMode.NOISE_CANCELLING), outcome)
        assertEquals(2, r.device.openCount)
    }

    @Test
    fun channelIsReleasedTwentySecondsAfterTheTap() = runTest {
        val r = rig()
        r.control.perform(NoiseControl.Request.Cycle)
        assertEquals(ConnectionPhase.CONNECTED, r.session.snapshot.value.phase)

        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(r.device.isOpen)

        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(ConnectionPhase.IDLE, r.session.snapshot.value.phase)
        assertFalse(r.device.isOpen)
    }
}
