import SwiftUI
import SonyHeadphonesKit

/// "Where it's playing": the device holding the headphones' playback right (what their buttons
/// and pause act on), its play state and track, the other devices with "Play here", and a hint
/// when this Mac's audio and the controls point to different devices.
struct PlaybackSourceSection: View {
    @EnvironmentObject private var controller: HeadphonesController
    @EnvironmentObject private var coordinator: PlaybackSourceCoordinator
    let capabilities: DeviceCapabilities

    private var source: PlaybackSourceState { controller.playbackSource }

    var body: some View {
        if let devices = source.devices, !devices.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                PlaybackSourceNotices(compact: false)
                if let active = source.playbackDevice {
                    hero(active)
                } else {
                    Text("None of the connected devices has the playback controls right now.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                let others = devices.filter { !$0.isPlayback }
                if !others.isEmpty {
                    Divider()
                    VStack(spacing: 2) {
                        ForEach(others) { otherRow($0) }
                    }
                }
            }
        } else if controller.initialStateTimedOut {
            Text("The headphones didn't report their device list.")
                .font(.caption)
                .foregroundStyle(.secondary)
        } else {
            LoadingRow()
        }
    }

    private func hero(_ device: MultipointDevice) -> some View {
        HStack(spacing: 10) {
            ZStack {
                Circle().fill(Color.brand.opacity(0.14))
                if source.playbackStatus?.state == .playing {
                    PlayingBars(color: .brand)
                        .frame(width: 16, height: 14)
                } else {
                    Image(systemName: symbolForDevice(named: device.name))
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Color.brand)
                }
            }
            .frame(width: 36, height: 36)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(device.name)
                        .font(.callout.weight(.semibold))
                        .lineLimit(1)
                    if device.id == controller.thisDevice?.id { ThisDeviceTag() }
                    if source.switchingTo == device.macAddress {
                        ProgressView().controlSize(.mini)
                    }
                }
                // Longest version that fits: state · title — artist, then without the artist.
                ViewThatFits(in: .horizontal) {
                    ForEach(statusLines, id: \.self) { line in
                        Text(line).lineLimit(1)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            .layoutPriority(1)

            Spacer(minLength: 4)
            if capabilities.supports(.playbackControl), source.playbackStatus?.controlsAvailable != false {
                TransportControls(compact: false)
            }
        }
        .accessibilityElement(children: .contain)
    }

    /// "Playing · Title — Artist", "Playing · Title", "Playing"; the track only when the source shares it.
    private var statusLines: [String] {
        let state = playbackStateLabel(source.playbackStatus)
        guard let track = source.nowPlaying, let title = track.title else { return [state] }
        var lines = [state + " · " + title, state]
        if let artist = track.artist { lines.insert(state + " · " + title + " — " + artist, at: 0) }
        return lines
    }

    private func otherRow(_ device: MultipointDevice) -> some View {
        HStack(spacing: 10) {
            Image(systemName: symbolForDevice(named: device.name))
                .font(.system(size: 14))
                .foregroundStyle(device.isConnected ? Color.primary.opacity(0.75) : Color.secondary)
                .frame(width: 36)
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 6) {
                    Text(device.name)
                        .font(.callout)
                        .foregroundStyle(device.isConnected ? Color.primary : Color.secondary)
                        .lineLimit(1)
                    if device.id == controller.thisDevice?.id { ThisDeviceTag() }
                }
                Text(LocalizedStringKey(device.isConnected ? "Connected" : "Paired, not connected"))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            if device.isConnected && capabilities.supports(.playbackSourceSwitch) {
                if source.switchingTo == device.macAddress {
                    ProgressView().controlSize(.small)
                } else {
                    Button("Play here") { controller.switchPlayback(to: device) }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .help("Gives this device the headphone controls and the audio")
                }
            }
        }
        .padding(.vertical, 3)
    }
}

/// Banners shared by the card and the menu bar panel. Only one shows at a time, most useful first.
struct PlaybackSourceNotices: View {
    @EnvironmentObject private var controller: HeadphonesController
    @EnvironmentObject private var coordinator: PlaybackSourceCoordinator
    let compact: Bool

    var body: some View {
        if let advice = coordinator.advice, let interrupted = advice.interrupted {
            notice(Text("Music on \(interrupted.fromName) stopped when the headphones switched to \(name(of: interrupted.toMac))."),
                   button: Text("Back to \(interrupted.fromName) and resume")) {
                controller.switchPlayback(toAddress: interrupted.fromMac, resume: true)
            }
        } else if let advice = coordinator.advice, advice.controlsElsewhere, let other = advice.playbackDevice,
                  let here = advice.thisDevice {
            notice(Text("This Mac is sending audio, but the headphone buttons control \(other.name)."),
                   button: Text("Bring the controls here")) {
                controller.switchPlayback(to: here)
            }
        } else if let failure = controller.playbackSource.sourceSwitchFailure {
            notice(Text(LocalizedStringKey(failureMessage(failure))))
        }
    }

    private func name(of mac: String) -> String {
        controller.devices?.first { $0.macAddress == mac }?.name ?? mac
    }

    private func notice(_ text: Text, button: Text? = nil, action: @escaping () -> Void = {}) -> some View {
        HStack(alignment: .center, spacing: 8) {
            Image(systemName: "exclamationmark.circle")
                .foregroundStyle(StudioColor.warning)
            text
                .font(compact ? .caption2 : .caption)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            if let button {
                Button(action: action) { button }
                    .buttonStyle(.bordered)
                    .controlSize(compact ? .mini : .small)
            }
        }
        .padding(8)
        .background(StudioColor.warning.opacity(0.10), in: RoundedRectangle(cornerRadius: Radius.control))
    }

    private func failureMessage(_ failure: SourceSwitchResult) -> String {
        switch failure {
        case .failedCalling: return "Couldn't switch: there is a call in progress."
        case .failedA2dpNotConnected: return "Couldn't switch: that device doesn't have the audio connected."
        case .failedVoiceAssistant: return "Couldn't switch: the voice assistant has priority."
        default: return "The headphones didn't confirm the switch."
        }
    }
}

/// Previous / play-pause / next, relayed by the headphones to the device that has the controls.
struct TransportControls: View {
    @EnvironmentObject private var controller: HeadphonesController
    let compact: Bool

    private var isPlaying: Bool { controller.playbackSource.playbackStatus?.state == .playing }

    var body: some View {
        HStack(spacing: compact ? 2 : 4) {
            if !compact {
                button("backward.fill", label: "Previous track") { controller.controlPlayback(.previous) }
            }
            button(isPlaying ? "pause.fill" : "play.fill", label: isPlaying ? "Pause" : "Play", prominent: true) {
                controller.controlPlayback(isPlaying ? .pause : .play)
            }
            button("forward.fill", label: "Next track") { controller.controlPlayback(.next) }
        }
    }

    private func button(_ symbol: String, label: String, prominent: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: compact ? 10 : 12, weight: .semibold))
                .frame(width: compact ? 22 : 28, height: compact ? 22 : 28)
                .foregroundStyle(prominent ? Color.white : Color.primary.opacity(0.8))
                .background(Circle().fill(prominent ? Color.brand : Surface.well))
                .contentShape(Circle())
        }
        .buttonStyle(PressableButtonStyle(scale: 0.92))
        .help(Text(LocalizedStringKey(label)))
        .accessibilityLabel(Text(LocalizedStringKey(label)))
    }
}

/// Small "This Mac" capsule next to a device name.
struct ThisDeviceTag: View {
    var body: some View {
        Text("This Mac")
            .font(.caption2.weight(.medium))
            .foregroundStyle(Color.brand)
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(Color.brand.opacity(0.12), in: Capsule())
            .fixedSize()
    }
}

/// Three bars that move while something plays; still when Reduce Motion is on.
struct PlayingBars: View {
    var color: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 12, paused: reduceMotion)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            GeometryReader { geo in
                HStack(alignment: .bottom, spacing: geo.size.width * 0.18) {
                    ForEach(0..<3, id: \.self) { i in
                        let phase = reduceMotion ? 0.6 : 0.5 + 0.5 * sin(t * (5.2 + Double(i) * 1.7) + Double(i) * 1.3)
                        RoundedRectangle(cornerRadius: 1)
                            .fill(color)
                            .frame(height: geo.size.height * (0.3 + 0.7 * phase))
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height, alignment: .bottom)
            }
        }
        .accessibilityHidden(true)
    }
}

/// The menu bar version: one line saying where it plays, play/pause, and a way to move it.
struct CompactPlaybackSourceView: View {
    @EnvironmentObject private var controller: HeadphonesController
    let capabilities: DeviceCapabilities

    private var source: PlaybackSourceState { controller.playbackSource }

    var body: some View {
        if let devices = source.devices, !devices.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                PlaybackSourceNotices(compact: true)
                HStack(spacing: 6) {
                    Group {
                        if source.playbackStatus?.state == .playing {
                            PlayingBars(color: .brand).frame(width: 12, height: 10)
                        } else {
                            Image(systemName: "hifispeaker").font(.caption)
                        }
                    }
                    .foregroundStyle(Color.brand)
                    .frame(width: 16)
                    Text(headline)
                        .font(.caption)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Spacer(minLength: 4)
                    if capabilities.supports(.playbackControl), source.playbackStatus?.controlsAvailable != false,
                       source.playbackDevice != nil {
                        TransportControls(compact: true)
                    }
                }
                ForEach(devices.filter { $0.isConnected && !$0.isPlayback }) { device in
                    Button {
                        controller.switchPlayback(to: device)
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: symbolForDevice(named: device.name))
                                .font(.caption)
                                .frame(width: 16)
                                .foregroundStyle(.secondary)
                            Text(device.name)
                                .font(.caption)
                                .lineLimit(1)
                            Spacer()
                            if source.switchingTo == device.macAddress {
                                ProgressView().controlSize(.mini)
                            } else {
                                Text("Play here")
                                    .font(.caption2.weight(.medium))
                                    .foregroundStyle(Color.brand)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!capabilities.supports(.playbackSourceSwitch) || source.switchingTo != nil)
                }
            }
        }
    }

    /// "Playing on Pixel" / "Paused on Pixel" / "Controls on Pixel".
    private var headline: String {
        guard let device = source.playbackDevice else {
            return NSLocalizedString("No device has the controls", comment: "multipoint")
        }
        let format: String
        switch source.playbackStatus?.state {
        case .playing?: format = NSLocalizedString("Playing on %@", comment: "multipoint")
        case .paused?: format = NSLocalizedString("Paused on %@", comment: "multipoint")
        case .stopped?: format = NSLocalizedString("Stopped on %@", comment: "multipoint")
        default: format = NSLocalizedString("Controls on %@", comment: "multipoint")
        }
        let isHere = device.id == controller.thisDevice?.id
        return String(format: format, isHere ? NSLocalizedString("this Mac", comment: "multipoint") : device.name)
    }
}

/// "Playing", "Paused", "Stopped", "In a call", or "Has the controls" when unknown.
func playbackStateLabel(_ status: PlaybackStatus?) -> String {
    if status?.isCall == true { return NSLocalizedString("In a call", comment: "multipoint") }
    switch status?.state {
    case .playing?: return NSLocalizedString("Playing", comment: "multipoint")
    case .paused?: return NSLocalizedString("Paused", comment: "multipoint")
    case .stopped?: return NSLocalizedString("Stopped", comment: "multipoint")
    default: return NSLocalizedString("Has the controls", comment: "multipoint")
    }
}
