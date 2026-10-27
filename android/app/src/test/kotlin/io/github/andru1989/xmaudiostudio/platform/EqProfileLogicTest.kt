package io.github.andru1989.xmaudiostudio.platform

import io.github.andru1989.xmaudiostudio.core.device.DeviceCatalog
import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneState
import io.github.andru1989.xmaudiostudio.core.model.EqualizerPreset
import io.github.andru1989.xmaudiostudio.core.model.EqualizerState
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.presets.BuiltinProfiles
import io.github.andru1989.xmaudiostudio.core.presets.DeviceEqSetting
import io.github.andru1989.xmaudiostudio.core.presets.EqProfile
import io.github.andru1989.xmaudiostudio.core.presets.ProfileCategory
import io.github.andru1989.xmaudiostudio.core.session.ConnectionPhase
import io.github.andru1989.xmaudiostudio.core.session.DeviceTarget
import io.github.andru1989.xmaudiostudio.core.session.SessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqProfileLogicTest {

    private val xm6 = DeviceTarget("AA:BB:CC:DD:EE:FF", "WH-1000XM6", HeadphoneModel.WH_1000XM6)
    private val xm6Caps = DeviceCatalog.capabilities(HeadphoneModel.WH_1000XM6)
    private val xm5Caps = DeviceCatalog.capabilities(HeadphoneModel.WH_1000XM5)
    private val vocals = listOf(-2, -1, -1, 0, 1, 2, 3, 2, 0, 0)

    private fun user(id: String, steps: List<Int>, clearBass: Int? = null, model: String = "wh-1000xm6") =
        EqProfile(id, "Mío $id", ProfileCategory.USER, model, DeviceEqSetting(steps = steps, clearBass = clearBass))

    private val profiles = BuiltinProfiles.forModel("wh-1000xm6") + user("u1", listOf(1, 2, 3, 0, 0, 0, 0, 0, 0, 0))

    private fun custom(bands: List<Int>) = EqualizerState(EqualizerPreset.CUSTOM.code, bands)

    private val base = NoiseControlStatus(
        availability = Availability.READY, modelName = "WH-1000XM6", isDemo = false, mode = null,
        batteryLevel = 80, charging = false, freshness = Freshness.LIVE, link = LinkActivity.CONNECTED, lastKnownAtMs = null,
    )

    @Test
    fun ownProfilesComeFirstThenMeasuredSubjectiveAndSonyPresets() {
        val ordered = EqProfileLogic.ordered(profiles)
        assertEquals("u1", ordered.first().id)
        val categories = ordered.map { it.category }.distinct()
        assertEquals(listOf(ProfileCategory.USER, ProfileCategory.SUBJECTIVE, ProfileCategory.DEVICE_PRESET), categories)
        assertEquals(profiles.size, ordered.size)
    }

    @Test
    fun activeProfileMatchesCustomCurvesPresetsAndPrefersTheLastApplied() {
        assertEquals("builtin.clear-vocals", EqProfileLogic.activeProfile(profiles, custom(vocals), xm6Caps, null)?.id)
        assertEquals("builtin.sony-clear", EqProfileLogic.activeProfile(profiles, EqualizerState(0x31), xm6Caps, null)?.id)
        assertNull(EqProfileLogic.activeProfile(profiles, custom(List(10) { 5 }), xm6Caps, null))
        assertNull(EqProfileLogic.activeProfile(profiles, null, xm6Caps, null))

        // Two profiles with the same curve: the one applied last wins.
        val twin = user("twin", vocals)
        val withTwin = profiles + twin
        assertEquals("builtin.clear-vocals", EqProfileLogic.activeProfile(withTwin, custom(vocals), xm6Caps, null)?.id)
        assertEquals("twin", EqProfileLogic.activeProfile(withTwin, custom(vocals), xm6Caps, "twin")?.id)
    }

    @Test
    fun soundConnectUserSettingMatchesBySavedBands() {
        // 0xA1: a user setting made in Sound Connect; the headphones report its bands.
        val reported = EqualizerState(0xa1, vocals)
        assertEquals("builtin.clear-vocals", EqProfileLogic.activeProfile(profiles, reported, xm6Caps, null)?.id)
        // A known Sony preset never matches by bands.
        assertNull(EqProfileLogic.activeProfile(listOf(user("x", vocals)), EqualizerState(0x31, vocals), xm6Caps, null))
    }

    @Test
    fun clearBassIsPartOfTheCurveOnFiveBandModels() {
        val withBass = user("bass", listOf(0, 3, 6, 7, 5), clearBass = 2, model = "wh-1000xm5")
        val reported = EqualizerState(EqualizerPreset.CUSTOM.code, listOf(0, 3, 6, 7, 5), subtype = 0x00, clearBass = 2)
        assertEquals("bass", EqProfileLogic.activeProfile(listOf(withBass), reported, xm5Caps, null)?.id)
        assertNull(EqProfileLogic.activeProfile(listOf(withBass), reported.copy(clearBass = 0), xm5Caps, null))
    }

    @Test
    fun stepsWrapAroundAndStartAtTheEnds() {
        val ordered = EqProfileLogic.ordered(profiles)
        assertEquals(ordered[1].id, EqProfileLogic.neighbour(ordered, ordered[0].id, +1)?.id)
        assertEquals(ordered.last().id, EqProfileLogic.neighbour(ordered, ordered[0].id, -1)?.id)
        assertEquals(ordered.first().id, EqProfileLogic.neighbour(ordered, ordered.last().id, +1)?.id)
        // No active profile (unsaved curve): next starts at the first, previous at the last.
        assertEquals(ordered.first().id, EqProfileLogic.neighbour(ordered, null, +1)?.id)
        assertEquals(ordered.last().id, EqProfileLogic.neighbour(ordered, "gone", -1)?.id)
        assertNull(EqProfileLogic.neighbour(emptyList(), null, +1))
    }

    @Test
    fun commandsSendOnlyTheEqualizer() {
        val vocalsProfile = profiles.first { it.id == "builtin.clear-vocals" }
        assertEquals(listOf(DeviceCommand.SetEqualizerBands(vocals, null)), EqProfileLogic.commands(vocalsProfile, xm6Caps))
        assertEquals(
            listOf(DeviceCommand.SetEqualizerPreset(0x31)),
            EqProfileLogic.commands(profiles.first { it.id == "builtin.sony-clear" }, xm6Caps),
        )
        // A profile defines the whole curve: Clear Bass goes to 0 when the profile has none.
        val noBass = user("nb", listOf(0, 3, 6, 7, 5), model = "wh-1000xm5")
        assertEquals(listOf(DeviceCommand.SetEqualizerBands(listOf(0, 3, 6, 7, 5), 0)), EqProfileLogic.commands(noBass, xm5Caps))
    }

    @Test
    fun availabilityNeedsAnEqualizerModel() {
        assertEquals(Availability.NO_DEVICE, EqProfileLogic.availability(null, demoMode = false, hasBluetoothPermission = true))
        val mdr1000x = DeviceTarget("AA:BB:CC:DD:EE:05", "MDR-1000X", HeadphoneModel.MDR_1000X)
        assertEquals(Availability.UNSUPPORTED_MODEL, EqProfileLogic.availability(mdr1000x, demoMode = false, hasBluetoothPermission = true))
        assertEquals(Availability.PERMISSION_MISSING, EqProfileLogic.availability(xm6, demoMode = false, hasBluetoothPermission = false))
        assertEquals(Availability.READY, EqProfileLogic.availability(xm6, demoMode = true, hasBluetoothPermission = false))
    }

    @Test
    fun listeningModesOtherThanStandardBlockTheEqualizer() {
        assertFalse(EqProfileLogic.listeningModeBlocksEq(null))
        assertFalse(EqProfileLogic.listeningModeBlocksEq(ListeningMode.STANDARD))
        assertTrue(EqProfileLogic.listeningModeBlocksEq(ListeningMode.BACKGROUND_MUSIC))
        assertTrue(EqProfileLogic.listeningModeBlocksEq(ListeningMode.CINEMA))
    }

    @Test
    fun liveStatusShowsTheMatchedProfileOrAnUnsavedCurve() {
        val snapshot = SessionSnapshot(
            target = xm6, phase = ConnectionPhase.CONNECTED, stateIsLive = true,
            state = HeadphoneState(equalizer = custom(vocals), bgmEnabled = false, cinemaEnabled = false),
        )
        val status = EqProfileLogic.status(base, snapshot, profiles, demoMode = false, hasBluetoothPermission = true, lastAppliedId = null, lastKnown = null)
        assertEquals("builtin.clear-vocals", status.activeId)
        assertFalse(status.unsavedCurve)
        assertFalse(status.blocked)
        assertEquals("u1", status.profiles.first().id)
        // Battery is not part of the equalizer widget, so it never redraws it.
        assertNull(status.base.batteryLevel)

        val edited = snapshot.copy(state = snapshot.state.copy(equalizer = custom(List(10) { 4 }), cinemaEnabled = true))
        val unsaved = EqProfileLogic.status(base, edited, profiles, demoMode = false, hasBluetoothPermission = true, lastAppliedId = null, lastKnown = null)
        assertNull(unsaved.activeId)
        assertTrue(unsaved.unsavedCurve)
        assertTrue(unsaved.blocked)
    }

    @Test
    fun releasedChannelShowsTheLastProfileSeenLiveOnTheSameHeadphones() {
        // In memory but not live: never shown as current.
        val released = SessionSnapshot(target = xm6, phase = ConnectionPhase.IDLE, stateIsLive = false, state = HeadphoneState(equalizer = custom(vocals)))
        val known = LastKnownState(xm6.address.lowercase(), "WH-1000XM6", timestampMs = 1, eqProfileId = "builtin.rock")
        val status = EqProfileLogic.status(base, released, profiles, demoMode = false, hasBluetoothPermission = true, lastAppliedId = null, lastKnown = known)
        assertEquals("builtin.rock", status.activeId)
        assertFalse(status.unsavedCurve)

        val other = known.copy(address = "11:22:33:44:55:66")
        assertNull(EqProfileLogic.status(base, released, profiles, false, true, null, other).activeId)
        // A deleted profile is not shown as active.
        assertNull(EqProfileLogic.status(base, released, profiles, false, true, null, known.copy(eqProfileId = "deleted")).activeId)
    }

    @Test
    fun trackerPersistsTheLiveProfile() {
        val tracker = LastKnownTracker()
        val live = SessionSnapshot(target = xm6, phase = ConnectionPhase.CONNECTED, stateIsLive = true, state = HeadphoneState(equalizer = custom(vocals), battery = null, ambientSound = null))
        // Nothing worth keeping without noise mode or battery.
        assertNull(tracker.onSnapshot(live, 1, "builtin.clear-vocals"))
        val withBattery = live.copy(state = live.state.copy(battery = io.github.andru1989.xmaudiostudio.core.model.BatteryStatus(50, false)))
        assertEquals("builtin.clear-vocals", tracker.onSnapshot(withBattery, 2, "builtin.clear-vocals")?.eqProfileId)
        // A different profile is new content.
        assertEquals("builtin.rock", tracker.onSnapshot(withBattery, 3, "builtin.rock")?.eqProfileId)
        // Released: the record keeps the last profile seen live.
        assertEquals("builtin.rock", tracker.onSnapshot(withBattery.copy(stateIsLive = false, phase = ConnectionPhase.IDLE), 4)?.eqProfileId)
    }
}
