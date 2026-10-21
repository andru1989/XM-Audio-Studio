import SwiftUI
import SonyHeadphonesKit

struct ConnectionCard: View {
    @EnvironmentObject private var controller: HeadphonesController

    private var capabilities: DeviceCapabilities {
        DeviceCatalog.capabilities(HeadphoneModel.from(bluetoothName: controller.deviceName) ?? .wh1000xm6)
    }

    var body: some View {
        Card("Connected Devices", icon: "laptopcomputer.and.iphone") {
            devicesContent
            if capabilities.supports(.connectionPriority) || capabilities.supports(.audioCodec) {
                Divider()
                BluetoothConnectionSection(capabilities: capabilities)
            }
        }
    }

    private var devicesContent: some View {
        Group {
            if let devices = controller.devices, !devices.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(devices) { device in
                        deviceRow(device)
                        if device.id != devices.last?.id {
                            Divider()
                        }
                    }

                    // Only worth saying when there is actually another device to
                    // switch to; otherwise it's instructions for an impossible action.
                    if devices.contains(where: { $0.isConnected && !$0.isPlayback }) {
                        Text("Click a connected device to make it the playback source.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .padding(.top, 6)
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
    }

    private func deviceRow(_ device: MultipointDevice) -> some View {
        Button {
            if device.isConnected && !device.isPlayback {
                controller.switchPlayback(to: device)
            }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: deviceIcon(for: device.name))
                    .font(.system(size: 16))
                    .foregroundStyle(device.isConnected ? Color.brand : Color.secondary)
                    .frame(width: 24)

                VStack(alignment: .leading, spacing: 2) {
                    Text(device.name)
                        .font(.callout.weight(device.isPlayback ? .semibold : .regular))
                        .foregroundStyle(device.isConnected ? Color.primary : Color.secondary)
                    Text(device.isPlayback
                         ? "Playing"
                         : (device.isConnected ? "Connected" : "Paired, not connected"))
                        .font(.caption2)
                        .foregroundStyle(device.isPlayback ? Color.brand : Color.secondary)
                }

                Spacer()

                if device.isPlayback {
                    Image(systemName: "speaker.wave.2.fill")
                        .foregroundStyle(Color.brand)
                } else if device.isConnected {
                    Text("Play here")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(Color.brand)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Color.brand.opacity(0.12), in: Capsule())
                }
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableButtonStyle(scale: 0.985))
        .disabled(!device.isConnected || device.isPlayback)
        .accessibilityLabel(accessibilityDescription(for: device))
        .accessibilityAddTraits(device.isPlayback ? [.isButton, .isSelected] : .isButton)
        .accessibilityHint(
            device.isConnected && !device.isPlayback ? "Switches playback to this device" : ""
        )
    }

    private func accessibilityDescription(for device: MultipointDevice) -> String {
        let state = device.isPlayback
            ? "currently playing"
            : (device.isConnected ? "connected" : "paired, not connected")
        return "\(device.name), \(state)"
    }

    private func deviceIcon(for name: String) -> String {
        symbolForDevice(named: name)
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
