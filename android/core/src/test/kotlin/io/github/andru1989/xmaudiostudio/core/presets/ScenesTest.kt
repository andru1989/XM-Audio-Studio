package io.github.andru1989.xmaudiostudio.core.presets

import io.github.andru1989.xmaudiostudio.core.SharedFixtures
import io.github.andru1989.xmaudiostudio.core.bool
import io.github.andru1989.xmaudiostudio.core.device.DeviceCatalog
import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneState
import io.github.andru1989.xmaudiostudio.core.int
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundState
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.EqualizerState
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.session.DeviceTarget
import io.github.andru1989.xmaudiostudio.core.session.HeadphoneSession
import io.github.andru1989.xmaudiostudio.core.session.SimulatedXm6Channel
import io.github.andru1989.xmaudiostudio.core.str
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Same cases as macos/Tests/AudioStudioKitTests/SceneTests.swift, against the same shared files. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val json = Json

    private fun library(): ProfileLibrary {
        var n = 0
        return ProfileLibrary(ProfileStore(tmp.root.resolve("profiles.json")), now = { "2026-10-21T00:00:0${n}Z" }, newId = { "id-${++n}" })
    }

    // region Format

    @Test
    fun v1FilesStillDecodeAndAreRewrittenAsV2() {
        val v1 = SharedFixtures.file("preset-schema/examples/export-v1.json").readText()
        val profiles = ProfileCodec.decode(v1)
        assertTrue(profiles.all { it.scene == null })
        val encoded = ProfileCodec.encode(profiles, "2026-10-21T12:00:00Z")
        assertEquals(2, (json.parseToJsonElement(encoded) as JsonObject)["version"]!!.int)
        assertTrue("an EQ-only profile writes no scene key", !encoded.contains("\"scene\""))
        assertEquals(profiles, ProfileCodec.decode(encoded))
        // The built-in profiles (a version 1 file) are unchanged and carry no scene.
        assertTrue(BuiltinProfiles.all.all { it.scene == null })
        assertEquals(14, BuiltinProfiles.all.size)
    }

    @Test
    fun v2ExampleDecodesAndRoundTrips() {
        val profiles = ProfileCodec.decode(SharedFixtures.file("preset-schema/examples/export-v2.json").readText())
        assertEquals(4, profiles.size)
        val office = profiles[0].scene!!
        assertEquals(SceneNoiseControl(SceneNoiseMode.AMBIENT_SOUND, level = 12, focusOnVoice = true), office.noiseControl)
        assertEquals(SceneListeningMode.STANDARD, office.listeningMode)
        assertEquals(SceneRoomSize.MIDDLE, office.bgmRoomSize)
        assertEquals(true, office.speakToChat)
        assertEquals(true, office.pauseWhenTakenOff)
        assertEquals(SceneAutoPowerOff.WHEN_TAKEN_OFF, office.autoPowerOff)
        val plane = profiles[1].scene!!
        assertEquals(SceneNoiseControl(SceneNoiseMode.NOISE_CANCELLING), plane.noiseControl)
        assertNull(plane.pauseWhenTakenOff)
        assertEquals(0x30, profiles[1].deviceEq.presetCode)
        assertEquals("wh-1000xm4", profiles[2].model)
        assertNull(profiles[3].scene)
        assertEquals(profiles, ProfileCodec.decode(ProfileCodec.encode(profiles, "2026-10-21T12:00:00Z")))
    }

    @Test
    fun invalidSceneExamplesAreRejectedForTheRightReason() {
        fun reason(file: String): String {
            val text = SharedFixtures.file("preset-schema/examples/invalid/$file").readText()
            val error = runCatching { ProfileCodec.decode(text) }.exceptionOrNull()
            assertTrue("$file should be rejected", error is ProfileFormatException)
            return error!!.message!!
        }
        assertTrue(reason("scene-in-v1.json").contains("version 2"))
        assertTrue(reason("scene-empty.json").contains("empty"))
        assertTrue(reason("scene-level-out-of-range.json").contains("level"))
        assertTrue(reason("scene-unsupported-feature.json").contains("listeningMode"))
        assertTrue(reason("future-version.json").contains("newer"))
        listOf("scene-bad-noise-mode.json", "scene-noise-without-mode.json", "scene-bad-room-size.json", "scene-bad-auto-power-off.json")
            .forEach { assertTrue(it, reason(it).isNotBlank()) }
    }

    @Test
    fun validationFollowsTheModelCapabilities() {
        fun valid(model: String, scene: HeadphoneScene, steps: Int) =
            ProfileCodec.validate(EqProfile("x", "x", ProfileCategory.USER, model, DeviceEqSetting(steps = List(steps) { 0 }), scene = scene))
        val nc = HeadphoneScene(noiseControl = SceneNoiseControl(SceneNoiseMode.AMBIENT_SOUND, 20, true))
        assertNull(valid("wh-1000xm6", nc, 10))
        assertNull(valid("wh-1000xm5", nc, 5))
        assertNull(valid("wh-1000xm6", HeadphoneScene(autoPowerOff = SceneAutoPowerOff.OFF, pauseWhenTakenOff = false), 10))
        // XM5: wear detection and auto power off are planned, not implemented.
        assertTrue(valid("wh-1000xm5", HeadphoneScene(pauseWhenTakenOff = true), 5)!!.contains("pauseWhenTakenOff"))
        assertTrue(valid("wh-1000xm5", HeadphoneScene(autoPowerOff = SceneAutoPowerOff.OFF), 5)!!.contains("autoPowerOff"))
        assertTrue(valid("wh-1000xm3", HeadphoneScene(speakToChat = true), 5)!!.contains("speakToChat"))
        assertTrue(valid("wh-1000xm4", HeadphoneScene(bgmRoomSize = SceneRoomSize.SMALL), 5)!!.contains("bgmRoomSize"))
        assertTrue(valid("wh-1000xm6", HeadphoneScene(noiseControl = SceneNoiseControl(SceneNoiseMode.OFF, level = 0)), 10)!!.contains("level"))
    }

    // endregion
    // region Shared vectors

    private val vectors by lazy { SharedFixtures.json("preset-schema/scene-vectors.json") }

    private fun model(case: JsonObject) = HeadphoneModel.fromId(case["model"]!!.str)!!

    /** The scene vocabulary for what the headphones report, as session state. */
    private fun reported(o: JsonObject): HeadphoneState {
        val nc = o["noiseControl"]?.jsonObject
        val listening = o["listeningMode"]?.let { json.decodeFromJsonElement(SceneListeningMode.serializer(), it).mode }
        val eq = o["equalizer"]?.jsonObject
        return HeadphoneState(
            ambientSound = nc?.let {
                AmbientSoundState(
                    mode = json.decodeFromJsonElement(SceneNoiseMode.serializer(), it["mode"]!!).mode,
                    focusOnVoice = it["focusOnVoice"]!!.bool,
                    level = it["level"]!!.int,
                )
            },
            bgmEnabled = listening?.let { it == ListeningMode.BACKGROUND_MUSIC },
            cinemaEnabled = listening?.let { it == ListeningMode.CINEMA },
            bgmRoomSize = o["bgmRoomSize"]?.let { json.decodeFromJsonElement(SceneRoomSize.serializer(), it).size },
            speakToChatEnabled = o["speakToChat"]?.bool,
            pauseWhenTakenOff = o["pauseWhenTakenOff"]?.bool,
            automaticPowerOff = o["autoPowerOff"]?.let { json.decodeFromJsonElement(SceneAutoPowerOff.serializer(), it).mode },
            equalizer = eq?.let { e ->
                EqualizerState(e["presetCode"]!!.int, e["bands"]!!.jsonArray.map { it.int }, clearBass = e["clearBass"]?.int)
            },
        )
    }

    private fun intent(o: JsonObject): DeviceCommand = when (o["type"]!!.str) {
        "ambientSound" -> DeviceCommand.SetAmbientSound(
            json.decodeFromJsonElement(SceneNoiseMode.serializer(), o["mode"]!!).mode, o["focusOnVoice"]!!.bool, o["level"]!!.int,
        )
        "bgmRoomSize" -> DeviceCommand.SetBgmRoomSize(json.decodeFromJsonElement(SceneRoomSize.serializer(), o["roomSize"]!!).size)
        "listeningMode" -> DeviceCommand.SetListeningMode(json.decodeFromJsonElement(SceneListeningMode.serializer(), o["mode"]!!).mode)
        "eqBands" -> DeviceCommand.SetEqualizerBands(o["steps"]!!.jsonArray.map { it.int }, o["clearBass"]?.int)
        "eqPreset" -> DeviceCommand.SetEqualizerPreset(o["code"]!!.int)
        "speakToChat" -> DeviceCommand.SetSpeakToChat(o["enabled"]!!.bool)
        "pauseWhenTakenOff" -> DeviceCommand.SetPauseWhenTakenOff(o["enabled"]!!.bool)
        "autoPowerOff" -> DeviceCommand.SetAutomaticPowerOff(json.decodeFromJsonElement(SceneAutoPowerOff.serializer(), o["mode"]!!).mode)
        else -> error("unknown intent ${o["type"]}")
    }

    private fun changeName(change: SceneChange) = when (change) {
        is SceneChange.NoiseMode -> "noiseMode"
        is SceneChange.AmbientLevel -> "ambientLevel"
        is SceneChange.FocusOnVoice -> "focusOnVoice"
        is SceneChange.Listening -> "listeningMode"
        is SceneChange.RoomSize -> "bgmRoomSize"
        is SceneChange.SpeakToChat -> "speakToChat"
        is SceneChange.PauseWhenTakenOff -> "pauseWhenTakenOff"
        is SceneChange.AutoPowerOff -> "autoPowerOff"
    }

    @Test
    fun captureMatchesSharedVectors() {
        for (case in vectors["capture"]!!.jsonArray.map { it.jsonObject }) {
            val name = case["name"]!!.str
            val model = model(case)
            val state = reported(case["state"]!!.jsonObject)
            val expectedScene = case["scene"]!!.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement(HeadphoneScene.serializer(), it) }
            assertEquals(name, expectedScene, HeadphoneScenes.capture(state, DeviceCatalog.capabilities(model)))

            val expectedEq = case["deviceEq"]!!.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement(DeviceEqSetting.serializer(), it) }
            val saved = runCatching { library().saveScene("Escena", model.id, state) }
            if (expectedEq == null) {
                assertTrue(name, saved.exceptionOrNull() is ProfileFormatException)
            } else {
                val profile = saved.getOrThrow()
                assertEquals(name, expectedEq, profile.deviceEq)
                assertEquals(name, expectedScene, profile.scene)
                assertEquals(name, ProfileCategory.USER, profile.category)
            }
        }
    }

    @Test
    fun applyMatchesSharedVectors() {
        for (case in vectors["apply"]!!.jsonArray.map { it.jsonObject }) {
            val name = case["name"]!!.str
            val caps = DeviceCatalog.capabilities(model(case))
            val current = reported(case["current"]!!.jsonObject)
            val scene = json.decodeFromJsonElement(HeadphoneScene.serializer(), case["scene"]!!)
            val eq = case["eq"]!!.takeUnless { it is JsonNull }
                ?.let { json.decodeFromJsonElement(DeviceEqSetting.serializer(), it) }
                ?.let { HeadphoneScenes.eqCommand(it, caps) }
            val expected = case["intents"]!!.jsonArray.map { intent(it.jsonObject) }
            assertEquals(name, expected, HeadphoneScenes.commands(scene, current, caps, eq))
            assertEquals(name, case["changes"]!!.jsonArray.map { it.str }, HeadphoneScenes.changes(scene, current, caps).map(::changeName))
        }
    }

    // endregion
    // region Library and session

    @Test
    fun sceneSurvivesPersistenceDuplicateRenameAndExport() {
        val lib = library()
        val state = HeadphoneState(
            ambientSound = AmbientSoundState(AmbientSoundMode.AMBIENT_SOUND, focusOnVoice = true, level = 9),
            bgmEnabled = true, cinemaEnabled = false, bgmRoomSize = BgmRoomSize.LARGE,
            speakToChatEnabled = false, pauseWhenTakenOff = true, automaticPowerOff = AutomaticPowerOffMode.OFF,
            equalizer = EqualizerState(0xa0, listOf(1, 2, 3, 0, 0, 0, 0, 0, 0, 0)),
        )
        val saved = lib.saveScene("  Café  ", "wh-1000xm6", state)
        assertEquals("Café", saved.name)
        val copy = lib.duplicate(saved.id, "Café 2")
        lib.rename(copy.id, "Café B")
        lib.updateSteps(copy.id, List(10) { 1 })

        val reopened = ProfileLibrary(ProfileStore(tmp.root.resolve("profiles.json")))
        assertEquals(saved.scene, reopened.userProfiles[0].scene)
        assertEquals(saved.scene, reopened.userProfiles[1].scene)
        assertEquals(List(10) { 1 }, reopened.userProfiles[1].deviceEq.steps)

        val imported = library().importText(lib.exportText())
        assertEquals(listOf(saved.scene, saved.scene), imported.map { it.scene })
        assertTrue(runCatching { lib.saveScene("x", "mdr-1000x", state) }.exceptionOrNull() is ProfileFormatException)
    }

    private val xm6 = DeviceTarget("AA:BB:CC:DD:EE:FF", "WH-1000XM6", HeadphoneModel.WH_1000XM6)

    private fun TestScope.settle(ms: Long = 3_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** End to end against the byte-level XM6 simulator, with the channel released (reconnect path) and held. */
    @Test
    fun sceneIntentsReachTheSimulatedHeadphones() = runTest {
        for (held in listOf(false, true)) {
            val device = SimulatedXm6Channel(backgroundScope)
            val session = HeadphoneSession(device, backgroundScope)
            session.selectTarget(xm6)
            if (held) {
                session.setSurfaceVisible("test", true)
                settle()
            }
            val caps = DeviceCatalog.capabilities(HeadphoneModel.WH_1000XM6)
            val scene = HeadphoneScene(
                noiseControl = SceneNoiseControl(SceneNoiseMode.AMBIENT_SOUND, level = 5, focusOnVoice = true),
                listeningMode = SceneListeningMode.BACKGROUND_MUSIC,
                bgmRoomSize = SceneRoomSize.LARGE,
                speakToChat = true,
                pauseWhenTakenOff = false,
                autoPowerOff = SceneAutoPowerOff.WHEN_TAKEN_OFF,
            )
            val eq = HeadphoneScenes.eqCommand(DeviceEqSetting(presetCode = 0x31), caps)
            HeadphoneScenes.commands(scene, session.snapshot.value.state, caps, eq).forEach(session::execute)
            settle()

            val label = if (held) "held" else "released"
            // Byte 3 is "noise control on", byte 4 "ambient": ambient sound sets both.
            assertTrue(label, device.ancOn && device.ambient)
            assertEquals(label, 5, device.ambientLevel)
            assertTrue(label, device.focusOnVoice)
            assertTrue(label, device.bgmEnabled)
            assertEquals(label, BgmRoomSize.LARGE.code, device.bgmRoom)
            assertTrue(label, device.speakToChatEnabled)
            assertTrue(label, !device.pauseWhenTakenOff)
            assertEquals(label, AutomaticPowerOffMode.WHEN_TAKEN_OFF.code, device.autoPowerOff)
            assertEquals(label, 0x31, device.eqPreset)
            assertEquals(label, scene, HeadphoneScenes.capture(session.snapshot.value.state, caps))
        }
    }

    // endregion
}
