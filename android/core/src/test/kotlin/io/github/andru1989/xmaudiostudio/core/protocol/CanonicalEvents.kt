package io.github.andru1989.xmaudiostudio.core.protocol

import io.github.andru1989.xmaudiostudio.core.model.ProtocolVersion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Canonical JSON form of decoded events used by every shared protocol fixture; identical to
 * the Swift helper in macos/Tests/SonyHeadphonesKitTests/ConformanceTests.swift. Optional keys
 * (`clearBass`, `voiceFocus`) appear only when the decoder reports them.
 */
object CanonicalEvents {

    fun lowerCamel(name: String) = name.lowercase().split('_').let { parts ->
        parts.first() + parts.drop(1).joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
    }

    fun canonical(event: MdrEvent?): JsonElement = when (event) {
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
            event.state.clearBass?.let { put("clearBass", it) }
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
            event.config.voiceFocus?.let { put("voiceFocus", it) }
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
    fun normalise(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { normalise(it.value) })
        is JsonArray -> JsonArray(e.map(::normalise))
        is JsonPrimitive -> if (!e.isString && e.content.toIntOrNull() != null) JsonPrimitive(e.content.toInt()) else e
        else -> e
    }
}
