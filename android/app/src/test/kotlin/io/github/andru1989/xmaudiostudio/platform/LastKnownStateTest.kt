package io.github.andru1989.xmaudiostudio.platform

import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneState
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundState
import io.github.andru1989.xmaudiostudio.core.model.BatteryStatus
import io.github.andru1989.xmaudiostudio.core.session.ConnectionPhase
import io.github.andru1989.xmaudiostudio.core.session.DeviceTarget
import io.github.andru1989.xmaudiostudio.core.session.SessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastKnownStateTest {

    private val xm6 = DeviceTarget("AA:BB:CC:DD:EE:FF", "Mis XM6", HeadphoneModel.WH_1000XM6)

    private fun live(mode: AmbientSoundMode, battery: Int) = SessionSnapshot(
        target = xm6,
        phase = ConnectionPhase.CONNECTED,
        state = HeadphoneState(ambientSound = AmbientSoundState(mode = mode), battery = BatteryStatus(battery, false)),
        stateIsLive = true,
    )

    private val released = SessionSnapshot(target = xm6, phase = ConnectionPhase.IDLE)

    @Test
    fun encodesAndDecodes() {
        val state = LastKnownState(xm6.address, "WH-1000XM6", AmbientSoundMode.AMBIENT_SOUND, 55, true, timestampMs = 1_760_000_000_000)
        assertEquals(state, LastKnownState.decode(state.encode()))
        val noMode = state.copy(mode = null, batteryLevel = null)
        assertEquals(noMode, LastKnownState.decode(noMode.encode()))
    }

    @Test
    fun unreadableDataIsIgnored() {
        assertNull(LastKnownState.decode(null))
        assertNull(LastKnownState.decode(""))
        assertNull(LastKnownState.decode("{not json"))
        assertNull(LastKnownState.decode("""{"address":"x","modelName":"y","mode":"TURBO","timestampMs":1}"""))
        // Fields added by a later version do not break older readers.
        assertEquals(
            LastKnownState("x", "y", AmbientSoundMode.OFF, null, false, 1),
            LastKnownState.decode("""{"address":"x","modelName":"y","mode":"OFF","timestampMs":1,"future":true}"""),
        )
    }

    @Test
    fun recordsOnlyLiveState() {
        assertNull(LastKnownState.from(released, nowMs = 1))
        assertNull(LastKnownState.from(SessionSnapshot(), nowMs = 1))
        // Live but nothing reported yet.
        assertNull(LastKnownState.from(SessionSnapshot(target = xm6, phase = ConnectionPhase.CONNECTED, stateIsLive = true), nowMs = 1))
        assertEquals(
            LastKnownState(xm6.address, "WH-1000XM6", AmbientSoundMode.OFF, 30, false, timestampMs = 9),
            LastKnownState.from(live(AmbientSoundMode.OFF, 30), nowMs = 9),
        )
    }

    @Test
    fun trackerPersistsChangesAndTheMomentLiveStateEnds() {
        val tracker = LastKnownTracker()
        assertNull(tracker.onSnapshot(released, nowMs = 0))

        val first = tracker.onSnapshot(live(AmbientSoundMode.NOISE_CANCELLING, 80), nowMs = 1_000)
        assertEquals(AmbientSoundMode.NOISE_CANCELLING, first?.mode)
        assertEquals(1_000L, first?.timestampMs)

        // Same content: nothing to write.
        assertNull(tracker.onSnapshot(live(AmbientSoundMode.NOISE_CANCELLING, 80), nowMs = 2_000))

        val changed = tracker.onSnapshot(live(AmbientSoundMode.AMBIENT_SOUND, 80), nowMs = 3_000)
        assertEquals(AmbientSoundMode.AMBIENT_SOUND, changed?.mode)

        // Channel released 20 s later: the values were valid until then.
        val ended = tracker.onSnapshot(released, nowMs = 23_000)
        assertEquals(AmbientSoundMode.AMBIENT_SOUND, ended?.mode)
        assertEquals(23_000L, ended?.timestampMs)
        assertNull(tracker.onSnapshot(released, nowMs = 24_000))
    }
}
