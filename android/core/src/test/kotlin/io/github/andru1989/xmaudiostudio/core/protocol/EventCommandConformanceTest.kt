package io.github.andru1989.xmaudiostudio.core.protocol

import io.github.andru1989.xmaudiostudio.core.SharedFixtures
import io.github.andru1989.xmaudiostudio.core.bool
import io.github.andru1989.xmaudiostudio.core.int
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundState
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatSensitivity
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatTimeout
import io.github.andru1989.xmaudiostudio.core.protocol.CanonicalEvents.canonical
import io.github.andru1989.xmaudiostudio.core.protocol.CanonicalEvents.lowerCamel
import io.github.andru1989.xmaudiostudio.core.protocol.CanonicalEvents.normalise
import io.github.andru1989.xmaudiostudio.core.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Runs shared/protocol-fixtures/events.json and commands.json (WH-1000XM6). The Swift suite
 * (macos/Tests/SonyHeadphonesKitTests/ConformanceTests.swift) runs the same files, so both
 * apps provably interpret the same Sony bytes the same way. Other generations:
 * [MultiModelConformanceTest].
 */
class EventCommandConformanceTest {

    @Test
    fun decodesEveryEventFixture() {
        val cases = SharedFixtures.json("protocol-fixtures/events.json")["cases"]!!.jsonArray
        for (case in cases.map { it.jsonObject }) {
            val type = if (case["messageType"]!!.str == "command2") MessageType.COMMAND_2 else MessageType.COMMAND_1
            val event = MdrEventDecoder.decode(hexToBytes(case["payload"]!!.str), type)
            assertEquals(case["name"]!!.str, normalise(case["expect"]!!), normalise(canonical(event)))
        }
    }

    private fun build(name: String, a: JsonObject): ByteArray? = when (name) {
        "init" -> MdrCommands.init()
        "modelNameGet" -> MdrCommands.modelNameGet()
        "firmwareVersionGet" -> MdrCommands.firmwareVersionGet()
        "ambientSoundGet" -> MdrCommands.ambientSoundGet(a["subtype"]!!.int)
        "ambientSoundSet" -> MdrCommands.ambientSoundSet(
            AmbientSoundState(
                mode = AmbientSoundMode.entries.first { lowerCamel(it.name) == a["mode"]!!.str },
                focusOnVoice = a["focusOnVoice"]!!.bool,
                level = a["level"]!!.int,
                subtype = a["subtype"]!!.int,
                hasWindNoiseByte = a["hasWindNoiseByte"]!!.bool,
            ),
            committed = a["committed"]?.bool ?: true,
        )
        "batteryGet" -> MdrCommands.batteryGet()
        "equalizerGet" -> MdrCommands.equalizerGet(a["subtype"]!!.int)
        "equalizerCapabilityGet" -> MdrCommands.equalizerCapabilityGet()
        "equalizerPresetSet" -> MdrCommands.equalizerPresetSet(a["code"]!!.int, a["subtype"]!!.int)
        "equalizerBandsSet" -> MdrCommands.equalizerBandsSet(a["bands"]!!.jsonArray.map { it.int }, a["subtype"]!!.int)
        "bgmModeSet" -> MdrCommands.bgmModeSet(a["enabled"]!!.bool, BgmRoomSize.entries.first { lowerCamel(it.name) == a["roomSize"]!!.str })
        "upmixCinemaSet" -> MdrCommands.upmixCinemaSet(a["enabled"]!!.bool)
        "speakToChatEnabledSet" -> MdrCommands.speakToChatEnabledSet(a["enabled"]!!.bool)
        "speakToChatConfigSet" -> MdrCommands.speakToChatConfigSet(
            SpeakToChatConfig(
                SpeakToChatSensitivity.entries.first { lowerCamel(it.name) == a["sensitivity"]!!.str },
                SpeakToChatTimeout.entries.first { lowerCamel(it.name) == a["timeout"]!!.str },
            ),
        )
        "automaticPowerOffSet" -> MdrCommands.automaticPowerOffSet(
            if (a["mode"]!!.str == "off") AutomaticPowerOffMode.OFF else AutomaticPowerOffMode.WHEN_TAKEN_OFF,
        )
        "pauseWhenTakenOffGet" -> MdrCommands.pauseWhenTakenOffGet()
        "pauseWhenTakenOffSet" -> MdrCommands.pauseWhenTakenOffSet(a["enabled"]!!.bool)
        "deviceListGet" -> MdrCommands.deviceListGet()
        "sourceSwitchSet" -> MdrCommands.sourceSwitchSet(a["mac"]!!.str)
        else -> error("fixture command not mapped: $name")
    }

    @Test
    fun buildsEveryCommandFixture() {
        val cases = SharedFixtures.json("protocol-fixtures/commands.json")["cases"]!!.jsonArray
        for (case in cases.map { it.jsonObject }) {
            val name = case["command"]!!.str
            val payload = build(name, case["args"]!!.jsonObject)
            assertEquals("$name ${case["args"]}", case["payload"]!!.str, payload?.toHex())
        }
    }
}
