package io.github.andru1989.xmaudiostudio.core.session

import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.ProtocolVersion
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType
import io.github.andru1989.xmaudiostudio.core.protocol.bytes
import io.github.andru1989.xmaudiostudio.core.protocol.toHex
import io.github.andru1989.xmaudiostudio.core.protocol.u
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared session driving the MDR v1 and XM5 adapters against small simulated headsets. */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiModelSessionTest {

    private val xm3 = DeviceTarget("11:22:33:44:55:03", "WH-1000XM3", HeadphoneModel.WH_1000XM3)
    private val xm4 = DeviceTarget("11:22:33:44:55:04", "WH-1000XM4", HeadphoneModel.WH_1000XM4)
    private val xm5 = DeviceTarget("11:22:33:44:55:05", "WH-1000XM5", HeadphoneModel.WH_1000XM5)

    private fun TestScope.settle(ms: Long = 2_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun FakeMdrDevice.payloads() = commands.map { it.payload.toHex() }

    @Test
    fun v1HeadsetIsReadWithV1CommandsOnly() = runTest {
        val device = FakeV1Headphones(backgroundScope)
        val session = HeadphoneSession(device, backgroundScope)
        session.selectTarget(xm3)
        session.setSurfaceVisible("main", true)
        settle()

        val s = session.snapshot.value
        assertEquals(ConnectionPhase.CONNECTED, s.phase)
        assertEquals(ProtocolVersion.V1, s.state.protocolVersion)
        assertEquals("WH-1000XM3", s.state.deviceInfo.modelName)
        assertEquals(100, s.state.battery?.level)
        assertEquals(AmbientSoundMode.NOISE_CANCELLING, s.state.ambientSound?.mode)
        assertEquals(listOf(0, 0, 0, 0, 0), s.state.equalizer?.bands)
        assertEquals(0, s.state.equalizer?.clearBass)
        assertEquals(6, s.state.equalizerCapability?.bandCount)

        assertEquals(listOf("00 00", "04 01", "04 02", "10 00", "66 02", "56 01", "50 01 01"), device.payloads())
        assertTrue(device.commands.all { it.type == MessageType.COMMAND_1 })
        assertFalse("0x22 is POWER OFF on v1", device.poweredOff)
    }

    @Test
    fun v1WritesNoiseControlAndEqualizerWithClearBass() = runTest {
        val device = FakeV1Headphones(backgroundScope)
        val session = HeadphoneSession(device, backgroundScope)
        session.selectTarget(xm3)
        session.execute(DeviceCommand.SetAmbientSound(AmbientSoundMode.AMBIENT_SOUND, true, 12))
        session.execute(DeviceCommand.SetEqualizerBands(listOf(1, 0, -3, 2, 10), clearBass = 3))
        settle()

        val writes = device.payloads().filter { it.startsWith("68") || it.startsWith("58") }
        assertEquals(listOf("68 02 11 01 00 01 01 0c", "58 01 a0 06 0d 0b 0a 07 0c 14"), writes)
        val s = session.snapshot.value.state
        assertEquals(AmbientSoundMode.AMBIENT_SOUND, s.ambientSound?.mode)
        assertEquals(true, s.ambientSound?.focusOnVoice)
        assertEquals(12, s.ambientSound?.level)
        assertEquals(listOf(1, 0, -3, 2, 10), s.equalizer?.bands)
        assertEquals(3, s.equalizer?.clearBass)
        assertFalse(device.poweredOff)
    }

    @Test
    fun v1NotificationFromTheHeadsetUpdatesState() = runTest {
        val device = FakeV1Headphones(backgroundScope)
        val session = HeadphoneSession(device, backgroundScope)
        session.selectTarget(xm3)
        session.setSurfaceVisible("main", true)
        settle()
        // NC/AMB button on the headset: the XM3 capture's "off" notification.
        device.emitCommand(MessageType.COMMAND_1, bytes(0x69, 0x02, 0x00, 0x02, 0x02, 0x01, 0x00, 0x00))
        settle(500)
        assertEquals(AmbientSoundMode.OFF, session.snapshot.value.state.ambientSound?.mode)
    }

    @Test
    fun v1AdapterAbortsOnAV2InitReplyBeforeAnyWrite() = runTest {
        val device = FakeV1Headphones(backgroundScope).apply { initReply = bytes(0x01, 0x00, 0x03, 0x00, 0x30, 0x18, 0x00, 0x00) }
        val session = HeadphoneSession(device, backgroundScope)
        session.selectTarget(xm4)
        session.execute(DeviceCommand.SetEqualizerPreset(0x10))
        settle()
        assertEquals(ConnectionPhase.FAILED, session.snapshot.value.phase)
        assertTrue(session.snapshot.value.error is SessionError.ProtocolMismatch)
        assertEquals("only the init request was sent", listOf("00 00"), device.payloads())
    }

    @Test
    fun xm4SpeakToChatResendsOptionsBeforeEnabling() = runTest {
        val device = FakeV1Headphones(backgroundScope).apply { stcConfig = listOf(0x01, 0x01, 0x02) }
        val session = HeadphoneSession(device, backgroundScope)
        session.selectTarget(xm4)
        session.execute(DeviceCommand.SetSpeakToChat(true))
        settle()

        val payloads = device.payloads()
        assertTrue(payloads.containsAll(listOf("f6 05", "fa 05")))
        val writes = payloads.filter { it.startsWith("fc") || it.startsWith("f8") }
        assertEquals("options as read from the device, then enable", listOf("fc 05 00 01 01 02", "f8 05 01 01"), writes)
        assertEquals(true, session.snapshot.value.state.speakToChatEnabled)
        assertEquals(true, session.snapshot.value.state.speakToChatConfig?.voiceFocus)
    }

    @Test
    fun xm5IsReadAndWrittenWithItsOwnTypes() = runTest {
        val device = FakeXm5Headphones(backgroundScope)
        val session = HeadphoneSession(device, backgroundScope)
        session.selectTarget(xm5)
        session.setSurfaceVisible("main", true)
        settle()

        val s = session.snapshot.value.state
        assertEquals(ConnectionPhase.CONNECTED, session.snapshot.value.phase)
        assertEquals(70, s.battery?.level)
        assertEquals(AmbientSoundMode.NOISE_CANCELLING, s.ambientSound?.mode)
        assertEquals(listOf(1, 0, 3, -1, 2), s.equalizer?.bands)
        assertEquals(10, s.equalizer?.clearBass)
        assertEquals(false, s.speakToChatEnabled)

        // A band edit keeps the Clear Bass the headset reported (+10).
        session.execute(DeviceCommand.SetEqualizerBands(listOf(0, 0, 0, 0, 1)))
        // Auto power off is not documented for the XM5: the session refuses it outright.
        session.execute(DeviceCommand.SetAutomaticPowerOff(AutomaticPowerOffMode.OFF))
        settle()
        val payloads = device.payloads()
        assertTrue(payloads.contains("58 00 a0 06 14 0a 0a 0a 0a 0b"))
        assertTrue("no auto-power-off bytes", payloads.none { it.startsWith("26") || it.startsWith("28") })
        assertTrue("no XM6-only reads", payloads.none { it == "66 19" || it == "56 04" || it.startsWith("e6") })
        assertTrue(device.commands.all { it.type == MessageType.COMMAND_1 && it.payload.u(0) != 0x36 })
    }
}
