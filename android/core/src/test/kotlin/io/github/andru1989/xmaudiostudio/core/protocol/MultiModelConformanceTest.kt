package io.github.andru1989.xmaudiostudio.core.protocol

import io.github.andru1989.xmaudiostudio.core.SharedFixtures
import io.github.andru1989.xmaudiostudio.core.bool
import io.github.andru1989.xmaudiostudio.core.int
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatSensitivity
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatTimeout
import io.github.andru1989.xmaudiostudio.core.protocol.CanonicalEvents.canonical
import io.github.andru1989.xmaudiostudio.core.protocol.CanonicalEvents.lowerCamel
import io.github.andru1989.xmaudiostudio.core.protocol.CanonicalEvents.normalise
import io.github.andru1989.xmaudiostudio.core.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs shared/protocol-fixtures/{events,commands}-v1.json (MDR v1: XM2-XM4) and -xm5.json
 * (WH-1000XM5). The Swift suite (MultiModelConformanceTests.swift) runs the same files.
 */
class MultiModelConformanceTest {

    private fun runEvents(file: String, decode: (ByteArray, MessageType) -> MdrEvent?) {
        val cases = SharedFixtures.json(file)["cases"]!!.jsonArray
        assertTrue("$file has cases", cases.size >= 15)
        for (case in cases.map { it.jsonObject }) {
            val type = if (case["messageType"]!!.str == "command2") MessageType.COMMAND_2 else MessageType.COMMAND_1
            val event = decode(hexToBytes(case["payload"]!!.str), type)
            assertEquals("$file: ${case["name"]!!.str}", normalise(case["expect"]!!), normalise(canonical(event)))
        }
    }

    private fun runCommands(file: String, build: (String, JsonObject) -> ByteArray?) {
        val cases = SharedFixtures.json(file)["cases"]!!.jsonArray
        for (case in cases.map { it.jsonObject }) {
            val name = case["command"]!!.str
            val expected = case["payload"]!!.let { if (it is JsonNull) null else it.str }
            val payload = build(name, case["args"]!!.jsonObject)
            assertEquals("$file: $name ${case["args"]}", expected, payload?.toHex())
            if (payload != null && file.contains("v1")) {
                assertTrue("v1 must never build opcode 0x22 (power off): $name", payload.u(0) != MdrV1Commands.Op.POWER_OFF)
            }
        }
    }

    private fun mode(a: JsonObject) = AmbientSoundMode.entries.first { lowerCamel(it.name) == a["mode"]!!.str }

    private fun config(a: JsonObject) = SpeakToChatConfig(
        SpeakToChatSensitivity.entries.first { lowerCamel(it.name) == a["sensitivity"]!!.str },
        SpeakToChatTimeout.entries.first { lowerCamel(it.name) == a["timeout"]!!.str },
        a["voiceFocus"]?.bool,
    )

    @Test
    fun v1DecodesEveryEventFixture() = runEvents("protocol-fixtures/events-v1.json", MdrV1EventDecoder::decode)

    @Test
    fun v1BuildsEveryCommandFixture() = runCommands("protocol-fixtures/commands-v1.json") { name, a ->
        when (name) {
            "init" -> MdrCommands.init()
            "modelNameGet" -> MdrCommands.modelNameGet()
            "firmwareVersionGet" -> MdrCommands.firmwareVersionGet()
            "batteryGet" -> MdrV1Commands.batteryGet()
            "ambientSoundGet" -> MdrV1Commands.ambientSoundGet()
            "ambientSoundSet" -> MdrV1Commands.ambientSoundSet(mode(a), a["focusOnVoice"]!!.bool, a["level"]!!.int)
            "equalizerGet" -> MdrV1Commands.equalizerGet()
            "equalizerCapabilityGet" -> MdrV1Commands.equalizerCapabilityGet()
            "equalizerPresetSet" -> MdrV1Commands.equalizerPresetSet(a["code"]!!.int)
            "equalizerBandsSet" -> MdrV1Commands.equalizerBandsSet(a["bands"]!!.jsonArray.map { it.int }, a["clearBass"]!!.int)
            "speakToChatEnabledGet" -> MdrV1Commands.speakToChatEnabledGet()
            "speakToChatEnabledSet" -> MdrV1Commands.speakToChatEnabledSet(a["enabled"]!!.bool)
            "speakToChatConfigGet" -> MdrV1Commands.speakToChatConfigGet()
            "speakToChatConfigSet" -> MdrV1Commands.speakToChatConfigSet(config(a))
            else -> error("v1 fixture command not mapped: $name")
        }
    }

    @Test
    fun xm5DecodesEveryEventFixture() = runEvents("protocol-fixtures/events-xm5.json", Xm5EventDecoder::decode)

    @Test
    fun xm5BuildsEveryCommandFixture() = runCommands("protocol-fixtures/commands-xm5.json") { name, a ->
        when (name) {
            "ambientSoundGet" -> Xm5Commands.ambientSoundGet()
            "ambientSoundSet" -> Xm5Commands.ambientSoundSet(mode(a), a["focusOnVoice"]!!.bool, a["level"]!!.int)
            "batteryGet" -> Xm5Commands.batteryGet()
            "equalizerGet" -> Xm5Commands.equalizerGet()
            "equalizerCapabilityGet" -> Xm5Commands.equalizerCapabilityGet()
            "equalizerPresetSet" -> Xm5Commands.equalizerPresetSet(a["code"]!!.int)
            "equalizerBandsSet" -> Xm5Commands.equalizerBandsSet(a["bands"]!!.jsonArray.map { it.int }, a["clearBass"]!!.int)
            "speakToChatEnabledGet" -> Xm5Commands.speakToChatEnabledGet()
            "speakToChatEnabledSet" -> Xm5Commands.speakToChatEnabledSet(a["enabled"]!!.bool)
            "speakToChatConfigGet" -> Xm5Commands.speakToChatConfigGet()
            "speakToChatConfigSet" -> Xm5Commands.speakToChatConfigSet(config(a))
            else -> error("xm5 fixture command not mapped: $name")
        }
    }

    @Test
    fun eachDecoderOnlyAcceptsItsOwnFamily() {
        // The same bytes mean different things per family; a decoder must not "guess".
        val v1Battery = hexToBytes("11 00 64 00")
        val v2Battery = hexToBytes("23 00 64 00")
        assertEquals(null, MdrV1EventDecoder.decode(v2Battery, MessageType.COMMAND_1))
        assertEquals(null, Xm5EventDecoder.decode(v1Battery, MessageType.COMMAND_1))
        assertEquals(null, MdrEventDecoder.decode(v1Battery, MessageType.COMMAND_1))
    }
}
