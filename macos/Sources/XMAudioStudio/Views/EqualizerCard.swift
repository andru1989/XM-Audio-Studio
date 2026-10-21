import SwiftUI
import SonyHeadphonesKit
import AudioStudioKit

/// Equalizer preset chips, plus the band faders when the custom preset is active.
/// A section rather than its own card, sharing `SoundCard` with the listening mode.
struct EqualizerSection: View {
    @EnvironmentObject private var controller: HeadphonesController
    @EnvironmentObject private var studio: EqStudioModel
    @Environment(\.openWindow) private var openWindow

    private let columns = [GridItem(.adaptive(minimum: 92), spacing: 6)]

    private var effectivePresetCode: UInt8? {
        controller.equalizer?.presetCode ?? (controller.initialStateTimedOut ? EqualizerPreset.off.rawValue : nil)
    }

    /// The curve the faders edit. The headphones report ten bands; if a reply hasn't
    /// arrived yet, start from flat so the faders are still usable.
    private var bands: [Int] {
        let reported = controller.equalizer?.bands ?? []
        guard reported.count == SonyCommands.defaultBandCount else {
            return Array(repeating: 0, count: SonyCommands.defaultBandCount)
        }
        return reported
    }

    private var isCustomActive: Bool {
        effectivePresetCode == EqualizerPreset.custom.rawValue
    }

    var body: some View {
        CardSection("Equalizer", icon: "slider.vertical.3") {
            if let presetCode = effectivePresetCode {
                VStack(alignment: .leading, spacing: 10) {
                    if controller.equalizer == nil {
                        StateNotReportedBanner()
                    }

                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Active profile")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                            Text(studio.activeProfile?.name ?? NSLocalizedString("Custom (unsaved)", comment: ""))
                                .font(.callout.weight(.medium))
                        }
                        Spacer()
                        Button("Equalizer Studio…") {
                            openWindow(id: "equalizer-studio")
                            NSApp.activate(ignoringOtherApps: true)
                        }
                        .controlSize(.small)
                    }

                    if studio.deviceCurveNotInLibrary {
                        HStack(spacing: 8) {
                            Image(systemName: "square.and.arrow.down.on.square")
                                .foregroundStyle(Color.brand)
                            Text("Your headphones have a curve that is not in your library yet.")
                                .font(.caption)
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 4)
                            Button("Save to Library") {
                                studio.saveCurrent(as: studio.suggestedImportName)
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.small)
                            .help("Keep the curve the headphones have now as a profile in this app")
                        }
                        .padding(8)
                        .background(RoundedRectangle(cornerRadius: Radius.control, style: .continuous).fill(Surface.well))
                    }

                    LazyVGrid(columns: columns, spacing: 6) {
                        ForEach(EqualizerPreset.allCases) { option in
                            presetChip(option, isSelected: option.rawValue == presetCode)
                        }
                    }

                    if isCustomActive {
                        customBands
                            .transition(.opacity)
                    } else if EqualizerPreset(rawValue: presetCode) == nil {
                        Text("A personalized preset is active, set in the official app. Picking one above replaces it.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .animation(Motion.transition, value: isCustomActive)
            } else {
                LoadingRow()
            }
        }
    }

    // MARK: - Custom curve summary

    /// The dashboard only summarises the custom curve; the faders live in the Equalizer
    /// Studio window so the dashboard fits its default window size without scrolling.
    private var customBands: some View {
        let spec = studio.spec
        let grid = FrequencyGrid.logSpaced(min: 20, max: 20_000, count: 120)
        let curve = AutoEqImporter.deviceModel(spec).responseDb(steps: bands, at: grid)
        return VStack(alignment: .leading, spacing: 6) {
            Divider()
            ResponseChart(
                curves: [ChartCurve(frequencies: grid, gainsDb: curve, color: StudioColor.deviceEq, dashed: true, lineWidth: 1.8)],
                bandFrequencies: spec.bandFrequenciesHz,
                bandGainsDb: bands.map { Double($0) * spec.stepDb },
                showsDbLabels: false
            )
            .frame(height: 56)
            .accessibilityValue(bands.map { $0 > 0 ? "+\($0)" : "\($0)" }.joined(separator: " "))
        }
    }

    // MARK: - Presets

    private func presetChip(_ preset: EqualizerPreset, isSelected: Bool) -> some View {
        Button {
            controller.setEqualizerPreset(preset)
        } label: {
            Text(LocalizedStringKey(preset.label))
                .font(.caption.weight(isSelected ? .semibold : .regular))
                .lineLimit(1)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
                .foregroundStyle(isSelected ? Color.white : Color.primary.opacity(0.85))
                .controlSurface(
                    RoundedRectangle(cornerRadius: Radius.control),
                    isSelected: isSelected
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(PressableButtonStyle(scale: 0.96))
        .accessibilityLabel("\(preset.label) equalizer preset")
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }
}
