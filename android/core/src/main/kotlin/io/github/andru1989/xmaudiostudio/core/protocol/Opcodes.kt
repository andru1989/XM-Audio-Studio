package io.github.andru1989.xmaudiostudio.core.protocol

/**
 * Payload opcodes (`payload[0]`). Kept identical to macos/.../Protocol/Opcodes.swift;
 * see shared/protocol-spec/PROTOCOL.md for the families and their subtype bytes.
 */
object Opcode {
    const val INIT_REQUEST = 0x00
    const val INIT_REPLY = 0x01

    /** Same bytes on MDR v1 and v2: 04 01 model name, 04 02 firmware version. */
    const val DEVICE_INFO_GET = 0x04
    const val DEVICE_INFO_RET = 0x05

    const val EQ_CAPABILITY_GET = 0x50
    const val EQ_CAPABILITY_RET = 0x51

    const val BATTERY_GET = 0x22
    const val BATTERY_RET = 0x23
    const val BATTERY_NOTIFY = 0x25

    const val POWER_GET = 0x26
    const val POWER_RET = 0x27
    const val POWER_SET = 0x28
    const val POWER_NOTIFY = 0x29

    const val EQ_GET = 0x56
    const val EQ_RET = 0x57
    const val EQ_SET = 0x58
    const val EQ_NOTIFY = 0x59

    const val NCASM_GET = 0x66
    const val NCASM_RET = 0x67
    const val NCASM_SET = 0x68
    const val NCASM_NOTIFY = 0x69

    const val AUDIO_GET = 0xe6
    const val AUDIO_RET = 0xe7
    const val AUDIO_SET = 0xe8
    const val AUDIO_NOTIFY = 0xe9

    const val SYSTEM_GET = 0xf6
    const val SYSTEM_RET = 0xf7
    const val SYSTEM_SET = 0xf8
    const val SYSTEM_NOTIFY = 0xf9

    const val SYSTEM_EXT_GET = 0xfa
    const val SYSTEM_EXT_RET = 0xfb
    const val SYSTEM_EXT_SET = 0xfc
    const val SYSTEM_EXT_NOTIFY = 0xfd

    /** PERIPHERAL family, carried in COMMAND_2 (table 2) frames. */
    const val PERI_GET = 0x36
    const val PERI_RET = 0x37
    const val PERI_SET = 0x38
    const val PERI_NOTIFY = 0x39
    const val PERI_SET_EXTENDED = 0x3c
    const val PERI_NOTIFY_EXTENDED = 0x3d
}

object Subtype {
    const val NCASM_STANDARD = 0x15
    const val PAUSE_WHEN_TAKEN_OFF = 0x01
    const val SPEAK_TO_CHAT_ENABLED = 0x0c
    const val SPEAK_TO_CHAT_CONFIG = 0x0c
    const val AUTOMATIC_POWER_OFF = 0x05
    const val BGM_MODE = 0x09
    const val UPMIX_CINEMA = 0x04
    const val EQ_PRESET_AND_ERRORCODE = 0x04
    const val DEVICE_INFO_MODEL_NAME = 0x01
    const val DEVICE_INFO_FIRMWARE = 0x02
    const val BATTERY_SINGLE = 0x00
    const val BATTERY_SINGLE_WITH_THRESHOLD = 0x08
}
