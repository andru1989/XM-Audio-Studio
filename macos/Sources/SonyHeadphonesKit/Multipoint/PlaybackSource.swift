import Foundation

/// Where the audio is, as the headphones report it, plus what the app is doing about it.
/// Same transitions as Kotlin's `HeadphoneState` (android/core/.../device/HeadphoneState.kt);
/// see docs/decisions/ADR-0010.
public struct PlaybackSourceState: Equatable, Sendable {
    /// Multipoint list as shown: `reportedDevices` with a pending switch (`switchingTo`) applied.
    public private(set) var devices: [MultipointDevice]?
    /// Multipoint list exactly as the headphones last reported it.
    public private(set) var reportedDevices: [MultipointDevice]?
    /// MAC of a source switch sent but not yet confirmed (`3d 01`) nor expired.
    public private(set) var switchingTo: String?
    /// The source the user last chose here; a move to it is never an interruption.
    public private(set) var requestedSource: String?
    /// Why the last source switch was refused; cleared by the next switch or a success.
    public private(set) var sourceSwitchFailure: SourceSwitchResult?
    /// Play state of the device holding the playback right (`a3/a5 01`).
    public private(set) var playbackStatus: PlaybackStatus?
    /// Track names that device shares (`a7/a9 01`).
    public private(set) var nowPlaying: NowPlaying?
    /// Music that stopped because the headphones handed the playback right to another device.
    public private(set) var interruptedPlayback: InterruptedPlayback?

    public init() {}

    /// The device the headphones' buttons act on (playback right), as shown.
    public var playbackDevice: MultipointDevice? { devices?.first { $0.isPlayback } }

    /// A new list. A move of the playback right away from a device that was playing, to a device
    /// nobody picked here, is remembered as an interruption. While a switch is pending, a list read
    /// before the headphones finished switching does not flip the UI back to the old source.
    public mutating func applyList(_ list: [MultipointDevice]) {
        let oldHolder = reportedDevices?.first { $0.isPlayback }
        let newHolder = list.first { $0.isPlayback }
        let moved = oldHolder != nil && newHolder != nil && oldHolder!.macAddress != newHolder!.macAddress
        let toChosen = newHolder != nil && newHolder!.macAddress == requestedSource
        if switchingTo != nil || toChosen {
            interruptedPlayback = nil
        } else if moved, let oldHolder, let newHolder, playbackStatus?.state == .playing,
                  list.contains(where: { $0.macAddress == oldHolder.macAddress && $0.isConnected }) {
            interruptedPlayback = InterruptedPlayback(fromMac: oldHolder.macAddress, fromName: oldHolder.name, toMac: newHolder.macAddress)
        } else if let interrupted = interruptedPlayback,
                  newHolder?.macAddress == interrupted.fromMac
                    || !list.contains(where: { $0.macAddress == interrupted.fromMac && $0.isConnected }) {
            interruptedPlayback = nil
        }
        if !(switchingTo != nil || newHolder == nil || toChosen) { requestedSource = nil }
        reportedDevices = list
        devices = Self.withSwitch(list, to: switchingTo)
    }

    public mutating func applySwitchResult(_ result: SourceSwitchResult, macAddress: String) {
        if result == .success {
            let confirmed = (reportedDevices ?? devices).map { Self.withSwitch($0, to: macAddress) }
            reportedDevices = confirmed
            devices = confirmed
            switchingTo = nil
            sourceSwitchFailure = nil
            interruptedPlayback = nil
        } else {
            if switchingTo == nil || switchingTo == macAddress { sourceSwitchFailure = result }
            devices = reportedDevices ?? devices
            switchingTo = nil
            requestedSource = nil
        }
    }

    /// Something plays again: whatever stopped is no longer worth bringing back.
    public mutating func applyStatus(_ status: PlaybackStatus) {
        playbackStatus = status
        if status.state == .playing { interruptedPlayback = nil }
    }

    public mutating func applyTrack(_ track: NowPlaying) {
        nowPlaying = track
    }

    /// The user (or following) asked for `macAddress`: shown at once.
    public mutating func beginSwitch(to macAddress: String) {
        devices = (reportedDevices ?? devices).map { Self.withSwitch($0, to: macAddress) }
        switchingTo = macAddress
        requestedSource = macAddress
        sourceSwitchFailure = nil
        interruptedPlayback = nil
    }

    /// Ends a switch that was never confirmed: the reported list is the truth again.
    public mutating func expireSwitch(_ macAddress: String) {
        guard switchingTo == macAddress else { return }
        switchingTo = nil
        devices = reportedDevices ?? devices
    }

    /// Shown at once; the status the headphones report next reconciles it.
    public mutating func applyOptimistic(_ action: PlaybackAction) {
        var status = playbackStatus ?? PlaybackStatus(state: .unknown)
        switch action {
        case .play: status.state = .playing
        case .pause: status.state = .paused
        case .next, .previous: return
        }
        playbackStatus = status
    }

    /// `devices` with the playback flag moved to `macAddress`, when that device is listed and connected.
    static func withSwitch(_ devices: [MultipointDevice], to macAddress: String?) -> [MultipointDevice] {
        guard let macAddress, devices.contains(where: { $0.macAddress == macAddress && $0.isConnected }) else { return devices }
        return devices.map { d in
            var d = d
            d.isPlayback = d.macAddress == macAddress
            return d
        }
    }
}

public enum Multipoint {
    /// `AA:BB:..`, `aa-bb-..` and `aabb..` compare equal; Android's privacy placeholder never matches.
    public static func normalizeAddress(_ address: String?) -> String? {
        guard let address else { return nil }
        let hex = address.filter { $0.isLetter || $0.isNumber }.uppercased()
        return hex.count == 12 && hex != "020000000000" ? hex : nil
    }

    /// The list entry for the device this app runs on: by Bluetooth address (macOS knows its own),
    /// else by a name that matches exactly one entry.
    public static func thisDevice(_ devices: [MultipointDevice]?, localAddress: String?, localName: String?) -> MultipointDevice? {
        guard let devices else { return nil }
        if let address = normalizeAddress(localAddress),
           let match = devices.first(where: { normalizeAddress($0.macAddress) == address }) {
            return match
        }
        guard let name = localName?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { return nil }
        let matches = devices.filter { $0.name.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(name) == .orderedSame }
        return matches.count == 1 ? matches[0] : nil
    }
}

public struct SourceAdvice: Equatable, Sendable {
    public var thisDevice: MultipointDevice?
    /// The device the headphones' buttons act on (playback right).
    public var playbackDevice: MultipointDevice?
    /// This device is sending audio to the headphones, but their buttons (and pause) act on another
    /// device that is not playing: the "I can't pause it" situation.
    public var controlsElsewhere: Bool
    /// `controlsElsewhere` has lasted `settle`, following is on and allowed now.
    public var switchHereNow: Bool
    /// Music on `fromMac` stopped because the headphones gave the playback right to another device.
    public var interrupted: InterruptedPlayback?
    /// `interrupted` has lasted `settle`, following is on and allowed now.
    public var recoverNow: Bool
}

/// Same rules and constants as Kotlin's `PlaybackSourceAdvisor`.
public enum PlaybackSourceAdvisor {
    /// Local audio must last this long first: a notification sound never moves the controls.
    public static let settle: TimeInterval = 2
    /// At most one automatic switch in this window, so two devices can never ping-pong.
    public static let minInterval: TimeInterval = 15

    /// The device a claim moves the audio to, judged on fresh state: this device, when it is
    /// connected, does not already hold the playback right, no switch is pending and no call is in
    /// progress. Nil means "write nothing". Same as Kotlin's `claimTarget`.
    public static func claimTarget(_ state: PlaybackSourceState, localAddress: String?, localName: String?) -> MultipointDevice? {
        guard let here = Multipoint.thisDevice(state.reportedDevices ?? state.devices, localAddress: localAddress, localName: localName),
              here.isConnected, !here.isPlayback, state.switchingTo == nil, state.playbackStatus?.isCall != true else { return nil }
        return here
    }

    /// Whether this device should claim the audio now: following is on, it has been playing for
    /// `settle` (a notification sound never counts), this playback start was not claimed yet, and
    /// the last automatic action was at least `minInterval` ago. One claim per start, so two devices
    /// that both keep playing never ping-pong: the newest start wins.
    public static func shouldClaim(followPlayback: Bool, localPlayingSince: Date?, now: Date,
                                   claimedStart: Date?, lastAutoSwitch: Date?) -> Bool {
        guard followPlayback, let since = localPlayingSince, now.timeIntervalSince(since) >= settle,
              claimedStart != since else { return false }
        return lastAutoSwitch.map { now.timeIntervalSince($0) >= minInterval } ?? true
    }

    /// Never suggests taking the controls from a device that is playing, during a call, or while a
    /// switch is already pending.
    public static func advise(
        _ state: PlaybackSourceState,
        thisDevice: MultipointDevice?,
        localPlayingSince: Date?,
        now: Date,
        followPlayback: Bool,
        lastAutoSwitch: Date?,
        interruptedSince: Date? = nil
    ) -> SourceAdvice {
        let active = state.playbackDevice
        let status = state.playbackStatus
        var elsewhere = false
        if let thisDevice, thisDevice.isConnected, localPlayingSince != nil, state.switchingTo == nil,
           let active, active.macAddress != thisDevice.macAddress,
           status?.state != .playing, status?.isCall != true {
            elsewhere = true
        }
        let allowedNow = lastAutoSwitch.map { now.timeIntervalSince($0) >= minInterval } ?? true
        let switchNow = elsewhere && followPlayback && allowedNow
            && localPlayingSince.map { now.timeIntervalSince($0) >= settle } == true

        var interrupted: InterruptedPlayback?
        if let i = state.interruptedPlayback, state.switchingTo == nil, status?.state != .playing,
           status?.isCall != true, active?.macAddress != i.fromMac,
           state.devices?.contains(where: { $0.macAddress == i.fromMac && $0.isConnected }) == true {
            interrupted = i
        }
        let recoverNow = interrupted != nil && followPlayback && allowedNow && !switchNow
            && interruptedSince.map { now.timeIntervalSince($0) >= settle } == true
        return SourceAdvice(thisDevice: thisDevice, playbackDevice: active, controlsElsewhere: elsewhere,
                            switchHereNow: switchNow, interrupted: interrupted, recoverNow: recoverNow)
    }
}
