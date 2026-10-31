import Foundation

/// PLAYBACK_CONTROLLER status (`a3/a5 01 <enable> <status> <music/call>`): what the headphones know
/// about the device holding the playback right (the one their buttons control). Layout from
/// mos9527's MIT tables; mos9527 lists "current playing" as working on the XM6.
/// Mirrors android/core/.../model/Playback.kt.
public enum PlaybackState: UInt8, Sendable, CaseIterable {
    case unknown = 0x00
    case playing = 0x01
    case paused = 0x02
    case stopped = 0x03
}

public struct PlaybackStatus: Equatable, Sendable {
    public var state: PlaybackState
    /// The source is in a call rather than playing music.
    public var isCall: Bool
    /// ENABLE byte: false when the headphones say their playback controls are unavailable.
    public var controlsAvailable: Bool

    public init(state: PlaybackState, isCall: Bool = false, controlsAvailable: Bool = true) {
        self.state = state
        self.isCall = isCall
        self.controlsAvailable = controlsAvailable
    }
}

/// Track names the source shares over AVRCP (`a7/a9 01`); empty names are nil.
public struct NowPlaying: Equatable, Sendable {
    public var title: String?
    public var album: String?
    public var artist: String?

    public init(title: String? = nil, album: String? = nil, artist: String? = nil) {
        self.title = title
        self.album = album
        self.artist = artist
    }

    public var isEmpty: Bool { title == nil && album == nil && artist == nil }
}

/// `a4 01 00 <control>`: the headphones forward it (AVRCP) to the device holding the playback right.
public enum PlaybackAction: UInt8, Sendable, CaseIterable {
    case pause = 0x01
    case next = 0x02
    case previous = 0x03
    case play = 0x07
}

/// Result byte of `3d 01 <result> <mac>`, sent by the headphones after a playback source switch.
public enum SourceSwitchResult: Hashable, Sendable {
    case success, failed, failedCalling, failedA2dpNotConnected, failedVoiceAssistant
    /// A byte outside the reference table: treated as "not confirmed", never as success.
    case unknown(UInt8)

    init(code: UInt8) {
        switch code {
        case 0x00: self = .success
        case 0x01: self = .failed
        case 0x02: self = .failedCalling
        case 0x03: self = .failedA2dpNotConnected
        case 0x04: self = .failedVoiceAssistant
        default: self = .unknown(code)
        }
    }
}

/// The headphones moved the playback right away from `fromMac` while it was playing, to a device
/// the user did not pick, and the music stopped: the "it paused when I left the app" case.
public struct InterruptedPlayback: Equatable, Sendable {
    public var fromMac: String
    public var fromName: String
    public var toMac: String
}
