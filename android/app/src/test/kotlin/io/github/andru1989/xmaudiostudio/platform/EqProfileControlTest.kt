package io.github.andru1989.xmaudiostudio.platform

import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.presets.BuiltinProfiles
import io.github.andru1989.xmaudiostudio.core.presets.EqProfile
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType
import io.github.andru1989.xmaudiostudio.core.protocol.u
import io.github.andru1989.xmaudiostudio.core.session.ConnectionPhase
import io.github.andru1989.xmaudiostudio.core.session.DeviceTarget
import io.github.andru1989.xmaudiostudio.core.session.HeadphoneSession
import io.github.andru1989.xmaudiostudio.core.session.SimulatedXm6Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The equalizer widget's actions against the real session and the byte-level XM6 simulator. */
@OptIn(ExperimentalCoroutinesApi::class)
class EqProfileControlTest {

    private class Rig(scope: CoroutineScope) {
        val target = DeviceTarget("AA:BB:CC:DD:EE:FF", "WH-1000XM6", HeadphoneModel.WH_1000XM6)
        val device = SimulatedXm6Channel(scope) // custom curve 1 1 0 0 0 0 0 1 1 0: matches no profile
        val session = HeadphoneSession(device, scope)
        var availability = Availability.READY
        var lastApplied: String? = null
        val profiles: List<EqProfile> = BuiltinProfiles.forModel("wh-1000xm6")
        val ordered = EqProfileLogic.ordered(profiles)
        val control = EqProfileControl(
            device = { session },
            prepare = {
                session.selectTarget(target)
                availability
            },
            profiles = { profiles },
            lastAppliedId = { lastApplied },
            rememberApplied = { lastApplied = it },
        )

        fun payloads() = device.received.filter { it.type != MessageType.ACK }.map { it.payload }
        /** EQ writes (SET 0x58). */
        fun eqWrites() = payloads().filter { it.u(0) == 0x58 }
    }

    private fun TestScope.rig() = Rig(backgroundScope)

    @Test
    fun applyReadsTheRealStateThenWritesTheCurve() = runTest {
        val r = rig()
        val vocals = r.profiles.first { it.id == "builtin.clear-vocals" }

        assertEquals(EqProfileControl.Outcome.Sent(vocals), r.control.perform(EqProfileControl.Request.Apply(vocals.id)))

        assertEquals(vocals.deviceEq.steps, r.device.eqBands)
        assertEquals(0xa0, r.device.eqPreset)
        assertEquals(vocals.id, r.lastApplied)
        val payloads = r.payloads()
        val write = payloads.indexOfFirst { it.u(0) == 0x58 }
        assertTrue("state must be read before writing", payloads.indexOfLast { it.u(0) == 0x56 } in 0 until write)
    }

    @Test
    fun applyingTheActiveProfileWritesNothing() = runTest {
        val r = rig()
        r.device.eqBands = r.profiles.first { it.id == "builtin.rock" }.deviceEq.steps!!.toMutableList()
        val outcome = r.control.perform(EqProfileControl.Request.Apply("builtin.rock"))
        assertTrue(outcome is EqProfileControl.Outcome.AlreadyActive)
        assertTrue(r.eqWrites().isEmpty())
    }

    @Test
    fun sonyPresetIsSelectedByCode() = runTest {
        val r = rig()
        r.control.perform(EqProfileControl.Request.Apply("builtin.sony-clear"))
        assertEquals(0x31, r.device.eqPreset)
    }

    @Test
    fun nextAndPreviousStepThroughTheWidgetOrder() = runTest {
        val r = rig()
        // The simulator's curve matches nothing: "next" starts at the first profile.
        assertEquals(EqProfileControl.Outcome.Sent(r.ordered[0]), r.control.perform(EqProfileControl.Request.Step(+1)))
        assertEquals(EqProfileControl.Outcome.Sent(r.ordered[1]), r.control.perform(EqProfileControl.Request.Step(+1)))
        assertEquals(EqProfileControl.Outcome.Sent(r.ordered[0]), r.control.perform(EqProfileControl.Request.Step(-1)))
        assertEquals(EqProfileControl.Outcome.Sent(r.ordered.last()), r.control.perform(EqProfileControl.Request.Step(-1)))
        assertEquals(1, r.device.openCount)
    }

    @Test
    fun backgroundMusicBlocksTheEqualizerAndNothingIsWritten() = runTest {
        val r = rig()
        r.device.bgmEnabled = true
        assertEquals(EqProfileControl.Outcome.BlockedByListeningMode, r.control.perform(EqProfileControl.Request.Step(+1)))
        assertTrue(r.eqWrites().isEmpty())
    }

    @Test
    fun deletedProfileIsNotFound() = runTest {
        val r = rig()
        assertEquals(EqProfileControl.Outcome.NotFound, r.control.perform(EqProfileControl.Request.Apply("deleted")))
        assertTrue(r.eqWrites().isEmpty())
    }

    @Test
    fun unavailableOrFailedSendsNothing() = runTest {
        val r = rig()
        r.availability = Availability.PERMISSION_MISSING
        assertEquals(EqProfileControl.Outcome.Unavailable(Availability.PERMISSION_MISSING), r.control.perform(EqProfileControl.Request.Step(+1)))
        assertEquals(0, r.device.openCount)

        r.availability = Availability.READY
        r.device.refuseConnections = true
        assertTrue(r.control.perform(EqProfileControl.Request.Step(+1)) is EqProfileControl.Outcome.NotConnected)
        assertEquals(ConnectionPhase.FAILED, r.session.snapshot.value.phase)
        assertTrue(r.eqWrites().isEmpty())
    }
}
