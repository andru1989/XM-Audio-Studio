package io.github.andru1989.xmaudiostudio.core.device

import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.ConnectionPriority
import io.github.andru1989.xmaudiostudio.core.model.EqualizerState
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatSensitivity
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatTimeout
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType
import io.github.andru1989.xmaudiostudio.core.protocol.bytes
import io.github.andru1989.xmaudiostudio.core.protocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterSafetyTest {

    private val everyCommand = listOf(
        DeviceCommand.SetAmbientSound(AmbientSoundMode.NOISE_CANCELLING, false, 15),
        DeviceCommand.SetAmbientSound(AmbientSoundMode.AMBIENT_SOUND, true, 7),
        DeviceCommand.SetAmbientSound(AmbientSoundMode.OFF, false, 15),
        DeviceCommand.SetEqualizerPreset(0x16),
        DeviceCommand.SetEqualizerBands(listOf(1, 2, 3, 4, 5), clearBass = -2),
        DeviceCommand.SetEqualizerBands(listOf(1, 2, 3, 4, 5)),
        DeviceCommand.SetListeningMode(ListeningMode.BACKGROUND_MUSIC),
        DeviceCommand.SetBgmRoomSize(BgmRoomSize.LARGE),
        DeviceCommand.SetSpeakToChat(true),
        DeviceCommand.SetSpeakToChat(false),
        DeviceCommand.SetSpeakToChatConfig(SpeakToChatConfig(SpeakToChatSensitivity.HIGH, SpeakToChatTimeout.LONG)),
        DeviceCommand.SetAutomaticPowerOff(AutomaticPowerOffMode.OFF),
        DeviceCommand.SetPauseWhenTakenOff(true),
        DeviceCommand.SwitchPlayback("AA:BB:CC:DD:EE:01"),
        DeviceCommand.SetConnectionPriority(ConnectionPriority.STABLE_CONNECTION),
    )

    private val fiveBandState = HeadphoneState(equalizer = EqualizerState(0xa0, listOf(0, 0, 0, 0, 0), subtype = 0x01, clearBass = 4))

    private fun hex(out: List<Outgoing>) = out.map { "${if (it.type == MessageType.COMMAND_2) "T2 " else ""}${it.payload.toHex()}" }

    @Test
    fun xm6StateQueriesAreUnchanged() {
        assertEquals(
            listOf(
                "04 01", "04 02", "66 15", "66 17", "66 19", "22 00", "f6 0c", "fa 0c", "26 05", "f6 01",
                "56 04", "56 00", "50 04 01", "e6 09", "e6 04", "e0 05", "e6 05", "12 02", "T2 36 02",
            ),
            hex(Xm6Adapter.stateQueries()),
        )
        // The optional Clear Bass field does not change the XM6 bytes.
        assertEquals(
            listOf("58 04 a0 0a 09 08 07 06 05 04 03 06 06 06"),
            hex(Xm6Adapter.encode(DeviceCommand.SetEqualizerBands(listOf(3, 2, 1, 0, -1, -2, -3, 0, 0, 0), clearBass = 5), HeadphoneState())),
        )
    }

    @Test
    fun forModelPicksTheRightFamily() {
        assertEquals("xm6", ProtocolAdapter.forModel(HeadphoneModel.WH_1000XM6)?.id)
        assertEquals("xm5", ProtocolAdapter.forModel(HeadphoneModel.WH_1000XM5)?.id)
        for (m in listOf(HeadphoneModel.WH_1000XM4, HeadphoneModel.WH_1000XM3, HeadphoneModel.WH_1000XM2)) {
            assertEquals("mdr-v1", ProtocolAdapter.forModel(m)?.id)
        }
        assertEquals(null, ProtocolAdapter.forModel(HeadphoneModel.MDR_1000X))
    }

    @Test
    fun v1NeverEmitsPowerOffOrTable2() {
        for (model in listOf(HeadphoneModel.WH_1000XM4, HeadphoneModel.WH_1000XM3, HeadphoneModel.WH_1000XM2)) {
            val adapter = MdrV1Adapter(model)
            val all = adapter.stateQueries() + everyCommand.flatMap { adapter.encode(it, fiveBandState) }
            assertTrue(all.isNotEmpty())
            for (out in all) {
                assertEquals("${model.id}: v1 uses table 1 only", MessageType.COMMAND_1, out.type)
                assertTrue("${model.id}: 0x22 is POWER OFF on v1 (${out.payload.toHex()})", out.payload[0].toInt() != 0x22)
            }
            assertEquals(
                "a developer raw 0x22 is dropped on v1",
                emptyList<Outgoing>(),
                adapter.encode(DeviceCommand.Raw(MessageType.COMMAND_1, bytes(0x22, 0x00, 0x01)), HeadphoneState()),
            )
        }
    }

    @Test
    fun v1QueriesOnlyWhatTheModelHas() {
        assertEquals(
            listOf("04 01", "04 02", "10 00", "66 02", "56 01", "50 01 01"),
            hex(MdrV1Adapter(HeadphoneModel.WH_1000XM3).stateQueries()),
        )
        assertEquals(
            listOf("04 01", "04 02", "10 00", "66 02", "56 01", "50 01 01", "f6 05", "fa 05"),
            hex(MdrV1Adapter(HeadphoneModel.WH_1000XM4).stateQueries()),
        )
        // XM3 has no speak-to-chat: nothing is sent even if asked.
        assertEquals(emptyList<String>(), hex(MdrV1Adapter(HeadphoneModel.WH_1000XM3).encode(DeviceCommand.SetSpeakToChat(true), HeadphoneState())))
    }

    @Test
    fun v1EncodesAgainstTheDeviceState() {
        val xm4 = MdrV1Adapter(HeadphoneModel.WH_1000XM4)
        // Clear Bass omitted: the value the device reported (+4) is written back.
        assertEquals(listOf("58 01 a0 06 0e 0b 0c 0d 0e 0f"), hex(xm4.encode(DeviceCommand.SetEqualizerBands(listOf(1, 2, 3, 4, 5)), fiveBandState)))
        assertEquals(listOf("58 01 a0 06 08 0b 0c 0d 0e 0f"), hex(xm4.encode(DeviceCommand.SetEqualizerBands(listOf(1, 2, 3, 4, 5), clearBass = -2), fiveBandState)))
        // A 10-band curve is refused, never truncated.
        assertEquals(emptyList<String>(), hex(xm4.encode(DeviceCommand.SetEqualizerBands(List(10) { 0 }), fiveBandState)))

        val stc = HeadphoneState(speakToChatConfig = SpeakToChatConfig(SpeakToChatSensitivity.LOW, SpeakToChatTimeout.SHORT, voiceFocus = true))
        // Enabling re-sends the options first (XM4 note), keeping the device's voice focus.
        assertEquals(listOf("fc 05 00 02 01 00", "f8 05 01 01"), hex(xm4.encode(DeviceCommand.SetSpeakToChat(true), stc)))
        assertEquals(listOf("fc 05 00 00 00 01", "f8 05 01 01"), hex(xm4.encode(DeviceCommand.SetSpeakToChat(true), HeadphoneState())))
        assertEquals(listOf("f8 05 01 00"), hex(xm4.encode(DeviceCommand.SetSpeakToChat(false), stc)))
        assertEquals(
            listOf("fc 05 00 01 01 02"),
            hex(xm4.encode(DeviceCommand.SetSpeakToChatConfig(SpeakToChatConfig(SpeakToChatSensitivity.HIGH, SpeakToChatTimeout.LONG)), stc)),
        )
    }

    @Test
    fun xm5SendsNothingForUndocumentedFeatures() {
        val state = fiveBandState.copy(equalizer = fiveBandState.equalizer!!.copy(subtype = 0x00))
        for (command in listOf(
            DeviceCommand.SetAutomaticPowerOff(AutomaticPowerOffMode.OFF),
            DeviceCommand.SetPauseWhenTakenOff(true),
            DeviceCommand.SetListeningMode(ListeningMode.CINEMA),
            DeviceCommand.SetBgmRoomSize(BgmRoomSize.SMALL),
            DeviceCommand.SwitchPlayback("AA:BB:CC:DD:EE:01"),
            DeviceCommand.SetEqualizerBands(List(10) { 0 }),
            DeviceCommand.SetConnectionPriority(ConnectionPriority.STABLE_CONNECTION),
        )) {
            assertEquals(command.toString(), emptyList<Outgoing>(), Xm5Adapter.encode(command, state))
        }
        assertEquals(listOf("58 00 a0 06 0e 0b 0c 0d 0e 0f"), hex(Xm5Adapter.encode(DeviceCommand.SetEqualizerBands(listOf(1, 2, 3, 4, 5)), state)))
        assertEquals(
            listOf("04 01", "04 02", "66 17", "22 00", "f6 0c", "fa 0c", "56 00", "50 00 01"),
            hex(Xm5Adapter.stateQueries()),
        )
    }
}
