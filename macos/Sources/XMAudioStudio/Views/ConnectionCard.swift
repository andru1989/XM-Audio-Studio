import SwiftUI
import SonyHeadphonesKit

struct ConnectionCard: View {
    @EnvironmentObject private var controller: HeadphonesController

    private var capabilities: DeviceCapabilities {
        DeviceCatalog.capabilities(HeadphoneModel.from(bluetoothName: controller.deviceName) ?? .wh1000xm6)
    }

    var body: some View {
        Card("Where it's playing", icon: "hifispeaker.and.homepod") {
            PlaybackSourceSection(capabilities: capabilities)
            if capabilities.supports(.connectionPriority) || capabilities.supports(.audioCodec) {
                Divider()
                BluetoothConnectionSection(capabilities: capabilities)
            }
        }
    }
}

/// The Bluetooth link as the official app shows it: the codec in use (and LE Audio vs
/// classic when the headphones said so) and the connection priority. Only the two
/// priorities verified on hardware can be chosen; low latency is shown, never written.
struct BluetoothConnectionSection: View {
    @EnvironmentObject private var controller: HeadphonesController
    let capabilities: DeviceCapabilities

    /// The identity and list queries go out after the codec one, so once they are in
    /// a missing codec means the headphones didn't answer it.
    private var stateReadFinished: Bool {
        controller.devices != nil || controller.initialStateTimedOut
    }

    var body: some View {
        CardSection("Bluetooth Connection", icon: "antenna.radiowaves.left.and.right") {
            VStack(alignment: .leading, spacing: 8) {
                if capabilities.supports(.audioCodec) {
                    SettingsRow("Codec") {
                        Text(linkDescription)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                if capabilities.supports(.connectionPriority) {
                    priorityControl
                }
            }
        }
    }

    /// "LDAC · Classic Bluetooth"; either part may be missing.
    private var linkDescription: String {
        var parts: [String] = []
        switch controller.audioCodec {
        case .unknown(let code)?:
            parts.append(String(format: NSLocalizedString("Unknown (0x%02X)", comment: "codec byte"), code))
        case let codec?:
            parts.append(codec.displayName ?? "")
        case nil:
            break
        }
        switch controller.audioStream {
        case .leAudio?: parts.append("LE Audio")
        case .classic?: parts.append(NSLocalizedString("Classic Bluetooth", comment: ""))
        default: break
        }
        if parts.isEmpty {
            return stateReadFinished ? NSLocalizedString("Not reported", comment: "codec") : "…"
        }
        return parts.joined(separator: " · ")
    }

    /// The writable priorities the headphones offer, plus low latency (read-only) when it
    /// is the current one, so the radio group never shows nothing selected.
    private func options(current: ConnectionPriority) -> [ConnectionPriority] {
        var options = ConnectionPriority.allCases.filter { controller.canWriteConnectionPriority($0) }
        if !options.contains(current) { options.append(current) }
        return options
    }

    @ViewBuilder
    private var priorityControl: some View {
        if let current = controller.connectionPriority {
            Picker("Priority", selection: Binding(
                get: { current },
                set: { controller.setConnectionPriority($0) } // ignores low latency
            )) {
                ForEach(options(current: current)) { option in
                    Text(LocalizedStringKey(option.isWritable ? option.label : option.label + " (read-only)"))
                        .tag(option)
                }
            }
            .pickerStyle(.radioGroup)
            .labelsHidden()

            Label("Changing it can cut the Bluetooth audio for a moment.", systemImage: "exclamationmark.triangle")
                .font(.caption2)
                .foregroundStyle(.secondary)
        } else if stateReadFinished {
            Text("The headphones didn't report their connection priority.")
                .font(.caption)
                .foregroundStyle(.secondary)
        } else {
            LoadingRow()
        }
    }
}

/// Guesses a glyph from the device's advertised name. Shared with the menu bar panel
/// so a device doesn't appear as a laptop in one place and a generic box in another.
func symbolForDevice(named name: String) -> String {
    let lowered = name.lowercased()
    if lowered.contains("iphone") || lowered.contains("phone") { return "iphone" }
    if lowered.contains("ipad") || lowered.contains("tab") { return "ipad" }
    if lowered.contains("watch") { return "applewatch" }
    if lowered.contains("book") || lowered.contains("mac") || lowered.contains("pc") { return "laptopcomputer" }
    return "desktopcomputer"
}
