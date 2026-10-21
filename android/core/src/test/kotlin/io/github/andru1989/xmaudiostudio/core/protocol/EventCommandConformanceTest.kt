package io.github.andru1989.xmaudiostudio.core.protocol

import io.github.andru1989.xmaudiostudio.core.SharedFixtures
import io.github.andru1989.xmaudiostudio.core.bool
import io.github.andru1989.xmaudiostudio.core.int
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundState
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.ProtocolVersion
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatSensitivity
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatTimeout
import io.github.andru1989.xmaudiostudio.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Runs shared/protocol-fixtures/events.json and commands.json. The Swift suite
 * (macos/Tests/SonyHeadphonesKitTests/ConformanceTests.swift) runs the same files, so both
 * apps provably interpret the same Sony bytes the same way.
 */
class EventCommandConformanceTest {

    private fun lowerCamel(name: String) = name.lowercase().split('_').let { parts ->
        parts.first() + parts.drop(1).joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
    }

    /** Canonical JSON form of an event, identical to the Swift test helper's. */
    private fun canonical(event: MdrEvent?): JsonElement = when (event) {
        null -> JsonNull
        is MdrEvent.ProtocolInfo -> buildJsonObject {
            put("event", "protocolInfo")
            put("version", when (event.version) { ProtocolVersion.V1 -> "v1"; ProtocolVersion.V2 -> "v2"; ProtocolVersion.UNKNOWN -> "unknown" })
        }
        is MdrEvent.AmbientSound -> buildJsonObject {
            val s = event.state
            put("event", "ambientSound")
            put("mode", lowerCamel(s.mode.name))
            put("focusOnVoice", s.focusOnVoice)
            put("level", s.level)
            put("subtype", s.subtype)
            put("isUpdate", s.isUpdate)
            put("hasWindNoiseByte", s.hasWindNoiseByte)
        }
        is MdrEvent.Battery -> buildJsonObject {
            put("event", "battery"); put("level", event.status.level); put("charging", event.status.isCharging)
        }
        is MdrEvent.Equalizer -> buildJsonObject {
            put("event", "equalizer")
            put("presetCode", event.state.presetCode)
            put("bands", buildJsonArray { event.state.bands.forEach { add(JsonPrimitive(it)) } })
            put("subtype", event.state.subtype)
        }
        is MdrEvent.EqCapability -> buildJsonObject {
            val c = event.capability
            put("event", "eqCapability"); put("subtype", c.subtype); put("bandCount", c.bandCount); put("stepCount", c.stepCount)
            put("presetCodes", buildJsonArray { c.presetCodes.forEach { add(JsonPrimitive(it)) } })
        }
        is MdrEvent.BgmMode -> buildJsonObject {
            put("event", "bgmMode"); put("enabled", event.enabled); put("roomSize", lowerCamel(event.roomSize.name))
        }
        is MdrEvent.UpmixCinema -> buildJsonObject { put("event", "upmixCinema"); put("enabled", event.enabled) }
        is MdrEvent.SpeakToChatEnabled -> buildJsonObject { put("event", "speakToChatEnabled"); put("enabled", event.enabled) }
        is MdrEvent.SpeakToChat -> buildJsonObject {
            put("event", "speakToChatConfig")
            put("sensitivity", lowerCamel(event.config.sensitivity.name))
            put("timeout", lowerCamel(event.config.timeout.name))
        }
        is MdrEvent.AutomaticPowerOff -> buildJsonObject { put("event", "automaticPowerOff"); put("mode", lowerCamel(event.mode.name)) }
        is MdrEvent.PauseWhenTakenOff -> buildJsonObject { put("event", "pauseWhenTakenOff"); put("enabled", event.enabled) }
        is MdrEvent.DeviceList -> buildJsonObject {
            put("event", "deviceList")
            put("devices", buildJsonArray {
                event.devices.forEach { d ->
                    add(buildJsonObject { put("mac", d.macAddress); put("name", d.name); put("connected", d.isConnected); put("playback", d.isPlayback) })
                }
            })
        }
        is MdrEvent.ModelName -> buildJsonObject { put("event", "modelName"); put("name", event.name) }
        is MdrEvent.FirmwareVersion -> buildJsonObject { put("event", "firmwareVersion"); put("version", event.version) }
    }

    /** Numbers in fixtures are plain ints; normalise so 25 == 25 regardless of JSON number formatting. */
    private fun normalise(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { normalise(it.value) })
        is JsonArray -> JsonArray(e.map(::normalise))
        is JsonPrimitive -> if (!e.isString && e.content.toIntOrNull() != null) JsonPrimitive(e.content.toInt()) else e
        else -> e
    }

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
