import SwiftUI
import SonyHeadphonesKit
import AudioStudioKit

/// Flat pill next to the name of a profile that carries a scene (headphone settings besides the EQ).
struct SceneBadge: View {
    var body: some View {
        Text("Scene")
            .font(.caption2.weight(.semibold))
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill(StudioColor.deviceEq.opacity(0.18)))
            .foregroundStyle(StudioColor.deviceEq)
            .help("Includes headphone settings besides the equalizer")
            .accessibilityLabel("Scene: includes headphone settings besides the equalizer")
    }
}

/// Confirmation before applying a scene profile: every setting that changes, then the EQ.
struct SceneApplySheet: View {
    let profile: EqProfile
    @EnvironmentObject private var controller: HeadphonesController
    @EnvironmentObject private var studio: EqStudioModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let changes = studio.sceneChanges(for: profile)
        VStack(alignment: .leading, spacing: 10) {
            Text("Apply “\(profile.name)”").font(.headline)
            Text("This profile is a scene. Applying it changes:")
                .font(.callout)
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(changes.enumerated()), id: \.offset) { _, change in
                    bullet(SceneText.line(change))
                }
                bullet(String(format: NSLocalizedString("Equalizer: %@", comment: ""), studio.eqSummary(profile)))
            }
            if changes.isEmpty {
                Text("The other settings already match the scene.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if studio.sceneLeavesEqInactive(profile) {
                Label("The firmware turns the EQ off while the listening mode is Background Music or Cinema.", systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(StudioColor.warning)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if controller.connectionState != .connected {
                Text("State not read live: every saved setting is sent after the headphones are read.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("Apply") {
                    studio.apply(profile)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 420)
    }

    private func bullet(_ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text("•").foregroundStyle(.secondary)
            Text(text).fixedSize(horizontal: false, vertical: true)
        }
        .font(.callout)
    }
}

/// Localized lines for the scene confirmation, e.g. "Control de ruido: Sonido ambiente → Cancelación de ruido".
enum SceneText {
    private static func text(_ key: String) -> String { NSLocalizedString(key, comment: "") }

    private static func onOff(_ value: Bool?) -> String {
        switch value {
        case true?: return text("Enabled")
        case false?: return text("Disabled")
        case nil: return text("not read")
        }
    }

    private static func noise(_ mode: AmbientSoundMode?) -> String {
        switch mode {
        case .noiseCancelling?: return text("Noise Canceling")
        case .ambientSound?: return text("Ambient Sound")
        case .off?: return text("Off")
        case nil: return text("not read")
        }
    }

    private static func label(_ value: String?) -> String { value.map(text) ?? text("not read") }

    private static func line(_ title: String, _ from: String, _ to: String) -> String {
        "\(text(title)): \(from) → \(to)"
    }

    static func line(_ change: SceneChange) -> String {
        switch change {
        case let .noiseMode(from, to): return line("Noise control", noise(from), noise(to))
        case let .ambientLevel(from, to): return line("Ambient sound level", from.map(String.init) ?? text("not read"), String(to))
        case let .focusOnVoice(from, to): return line("Focus on Voice", onOff(from), onOff(to))
        case let .listeningMode(from, to): return line("Listening Mode", label(from?.label), label(to.label))
        case let .bgmRoomSize(from, to): return line("Background Music room", label(from?.label), label(to.label))
        case let .speakToChat(from, to): return line("Speak-to-Chat", onOff(from), onOff(to))
        case let .pauseWhenTakenOff(from, to): return line("Pause playback when taken off", onOff(from), onOff(to))
        case let .automaticPowerOff(from, to): return line("Automatic Power Off", label(from?.label), label(to.label))
        }
    }
}
