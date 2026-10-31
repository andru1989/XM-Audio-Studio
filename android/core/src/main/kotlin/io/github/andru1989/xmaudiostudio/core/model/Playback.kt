package io.github.andru1989.xmaudiostudio.core.model

/**
 * PLAYBACK_CONTROLLER status (`a3/a5 01 <enable> <status> <music/call>`): what the headphones know
 * about the device that holds the playback right (the one their buttons control).
 * Layout from mos9527's MIT tables; mos9527 lists "current playing" as working on the XM6.
 */
enum class PlaybackState(val code: Int) {
    UNKNOWN(0x00), PLAYING(0x01), PAUSED(0x02), STOPPED(0x03);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

data class PlaybackStatus(
    val state: PlaybackState,
    /** The source is in a call rather than playing music. */
    val isCall: Boolean = false,
    /** ENABLE byte: false when the headphones say their playback controls are unavailable. */
    val controlsAvailable: Boolean = true,
)

/** Track names the source shares over AVRCP (`a7/a9 01`); empty names are null. */
data class NowPlaying(val title: String? = null, val album: String? = null, val artist: String? = null) {
    val isEmpty: Boolean get() = title == null && album == null && artist == null
}

/** `a4 01 00 <control>`: the headphones forward it (AVRCP) to the device holding the playback right. */
enum class PlaybackAction(val code: Int) {
    PAUSE(0x01), NEXT(0x02), PREVIOUS(0x03), PLAY(0x07),
}

/** Result byte of `3d 01 <result> <mac>`, sent by the headphones after a playback source switch. */
enum class SourceSwitchResult(val code: Int) {
    SUCCESS(0x00),
    FAILED(0x01),
    FAILED_CALLING(0x02),
    FAILED_A2DP_NOT_CONNECTED(0x03),
    FAILED_VOICE_ASSISTANT(0x04),
    /** A byte outside the reference table: treated as "not confirmed", never as success. */
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * The headphones moved the playback right away from [fromMac] while it was playing, to a device
 * the user did not pick, and the music stopped: the "it paused when I left the app" case.
 */
data class InterruptedPlayback(val fromMac: String, val fromName: String, val toMac: String)
