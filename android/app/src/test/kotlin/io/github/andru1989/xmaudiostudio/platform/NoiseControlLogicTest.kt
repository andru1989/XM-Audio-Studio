package io.github.andru1989.xmaudiostudio.platform

import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
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

class NoiseControlLogicTest {

    private val xm6 = DeviceTarget("AA:BB:CC:DD:EE:FF", "WH-1000XM6", HeadphoneModel.WH_1000XM6)
    private val liveState = HeadphoneState(
        ambientSound = AmbientSoundState(mode = AmbientSoundMode.AMBIENT_SOUND, focusOnVoice = true, level = 7),
        battery = BatteryStatus(level = 64, isCharging = true),
    )

    @Test
    fun tileCyclesLikeTheHeadphoneButton() {
        assertEquals(AmbientSoundMode.AMBIENT_SOUND, NoiseControlLogic.nextMode(AmbientSoundMode.NOISE_CANCELLING))
        assertEquals(AmbientSoundMode.OFF, NoiseControlLogic.nextMode(AmbientSoundMode.AMBIENT_SOUND))
        assertEquals(AmbientSoundMode.NOISE_CANCELLING, NoiseControlLogic.nextMode(AmbientSoundMode.OFF))
    }

    @Test
    fun commandKeepsCompanionValuesFromTheHeadphones() {
        val current = AmbientSoundState(mode = AmbientSoundMode.NOISE_CANCELLING, focusOnVoice = true, level = 9)
        assertEquals(
            DeviceCommand.SetAmbientSound(AmbientSoundMode.AMBIENT_SOUND, focusOnVoice = true, level = 9),
            NoiseControlLogic.command(AmbientSoundMode.AMBIENT_SOUND, current),
        )
        // Level 0 can be reported but writes use 1..20 (same rule as the main screen).
        assertEquals(1, NoiseControlLogic.command(AmbientSoundMode.OFF, current.copy(level = 0)).level)
    }

    @Test
    fun availabilityRules() {
        assertEquals(Availability.NO_DEVICE, NoiseControlLogic.availability(null, demoMode = false, hasBluetoothPermission = true))
        val xm5 = DeviceTarget("AA:BB:CC:DD:EE:05", "WH-1000XM5", HeadphoneModel.WH_1000XM5)
        assertEquals(Availability.UNSUPPORTED_MODEL, NoiseControlLogic.availability(xm5, demoMode = false, hasBluetoothPermission = true))
        assertEquals(Availability.PERMISSION_MISSING, NoiseControlLogic.availability(xm6, demoMode = false, hasBluetoothPermission = false))
        // The simulator needs no Bluetooth permission.
        assertEquals(Availability.READY, NoiseControlLogic.availability(xm6, demoMode = true, hasBluetoothPermission = false))
        assertEquals(Availability.READY, NoiseControlLogic.availability(xm6, demoMode = false, hasBluetoothPermission = true))
    }

    @Test
    fun liveSnapshotWins() {
        val stale = LastKnownState(xm6.address, "WH-1000XM6", AmbientSoundMode.OFF, 10, false, timestampMs = 1)
        val snapshot = SessionSnapshot(target = xm6, phase = ConnectionPhase.CONNECTED, state = liveState, stateIsLive = true)
        val status = NoiseControlLogic.status(snapshot, demoMode = false, hasBluetoothPermission = true, lastKnown = stale)

        assertEquals(Availability.READY, status.availability)
        assertEquals(Freshness.LIVE, status.freshness)
        assertEquals(LinkActivity.CONNECTED, status.link)
        assertEquals(AmbientSoundMode.AMBIENT_SOUND, status.mode)
        assertEquals(64, status.batteryLevel)
        assertEquals(true, status.charging)
        assertEquals("WH-1000XM6", status.modelName)
        assertNull(status.lastKnownAtMs)
    }

    @Test
    fun releasedChannelShowsLastKnownStateOfTheSameHeadphones() {
        val known = LastKnownState(xm6.address.lowercase(), "WH-1000XM6", AmbientSoundMode.OFF, 40, false, timestampMs = 1_700_000_000_000)
        // Not live: whatever the session still holds in memory is not shown as current.
        val snapshot = SessionSnapshot(target = xm6, phase = ConnectionPhase.IDLE, state = liveState, stateIsLive = false)
        val status = NoiseControlLogic.status(snapshot, demoMode = false, hasBluetoothPermission = true, lastKnown = known)

        assertEquals(Freshness.LAST_KNOWN, status.freshness)
        assertEquals(LinkActivity.IDLE, status.link)
        assertEquals(AmbientSoundMode.OFF, status.mode)
        assertEquals(40, status.batteryLevel)
        assertEquals(1_700_000_000_000, status.lastKnownAtMs)
    }

    @Test
    fun lastKnownStateOfOtherHeadphonesIsIgnored() {
        val demoRecord = LastKnownState("00:00:00:00:DE:40", "WH-1000XM6", AmbientSoundMode.OFF, 72, false, timestampMs = 5)
        val snapshot = SessionSnapshot(target = xm6, phase = ConnectionPhase.CONNECTING)
        val status = NoiseControlLogic.status(snapshot, demoMode = false, hasBluetoothPermission = true, lastKnown = demoRecord)

        assertEquals(Freshness.UNKNOWN, status.freshness)
        assertEquals(LinkActivity.CONNECTING, status.link)
        assertNull(status.mode)
        assertNull(status.batteryLevel)
    }

    @Test
    fun noTargetIsUnavailable() {
        val status = NoiseControlLogic.status(SessionSnapshot(), demoMode = false, hasBluetoothPermission = true, lastKnown = null)
        assertEquals(Availability.NO_DEVICE, status.availability)
        assertNull(status.modelName)
        assertEquals(false, status.isDemo)
    }

    @Test
    fun phasesMapToLinkActivity() {
        assertEquals(LinkActivity.CONNECTING, NoiseControlLogic.link(ConnectionPhase.INITIALIZING))
        assertEquals(LinkActivity.CONNECTING, NoiseControlLogic.link(ConnectionPhase.READING_STATE))
        assertEquals(LinkActivity.YIELDED, NoiseControlLogic.link(ConnectionPhase.YIELDED))
        assertEquals(LinkActivity.FAILED, NoiseControlLogic.link(ConnectionPhase.FAILED))
    }
}
