import WidgetKit
import SwiftUI
import AppIntents
import WidgetBridgeKit

struct HeadphonesWidgetView: View {
    let entry: HeadphonesEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Group {
            switch family {
            case .systemSmall: small
            case .systemMedium: medium
            default: large
            }
        }
        .environment(\.palette, WidgetPalette(scheme: scheme))
    }

    private var snapshot: WidgetSnapshot? { entry.snapshot }

    // MARK: Sizes

    /// Battery and current mode.
    private var small: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                ProductPhoto(modelID: snapshot?.modelID, size: 44)
                Spacer(minLength: 4)
                if snapshot?.isDemo == true { DemoBadge() }
            }
            Spacer(minLength: 6)
            BatteryLabel(snapshot: snapshot, font: .system(size: 26, weight: .semibold, design: .rounded))
            NoiseModeLabel(snapshot: snapshot, pending: entry.pending)
                .padding(.top, 2)
            Spacer(minLength: 6)
            StatusLine(entry: entry)
        }
    }

    /// Header plus the three noise-control buttons.
    private var medium: some View {
        VStack(alignment: .leading, spacing: 10) {
            Header(entry: entry, photoSize: 40)
            NoiseButtons(entry: entry, circle: 36)
            Spacer(minLength: 0)
        }
    }

    /// Header, noise control and the equalizer presets.
    private var large: some View {
        VStack(alignment: .leading, spacing: 12) {
            Header(entry: entry, photoSize: 52)
            Divider()
            SectionTitle("Noise control")
            NoiseButtons(entry: entry, circle: 44)
            Divider()
            HStack(alignment: .firstTextBaseline) {
                SectionTitle("Equalizer")
                Spacer()
                if let name = currentEqName {
                    Text(name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            EqPresetGrid(entry: entry)
            if let mode = snapshot?.listeningMode, mode != .standard {
                Text("The EQ is off while Background Music or Cinema is on.")
                    .font(.caption2).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
    }

    private var currentEqName: String? {
        if let name = snapshot?.eqProfileName, !name.isEmpty { return name }
        guard let code = snapshot?.eqPresetCode else { return nil }
        guard let preset = EqPresetValue(code: code) else { return NSLocalizedString("Personalized", comment: "") }
        return NSLocalizedString(preset.label, comment: "")
    }
}

// MARK: - Pieces

private struct Header: View {
    let entry: HeadphonesEntry
    let photoSize: CGFloat

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            ProductPhoto(modelID: entry.snapshot?.modelID, size: photoSize)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(entry.snapshot?.deviceName ?? "XM Audio Studio")
                        .font(.headline).lineLimit(1)
                    if entry.snapshot?.isDemo == true { DemoBadge() }
                }
                StatusLine(entry: entry)
            }
            Spacer(minLength: 4)
            BatteryLabel(snapshot: entry.snapshot, font: .system(size: 17, weight: .semibold, design: .rounded))
        }
    }
}

private struct SectionTitle: View {
    let title: LocalizedStringKey
    init(_ title: LocalizedStringKey) { self.title = title }

    var body: some View {
        Text(title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
    }
}

struct ProductPhoto: View {
    let modelID: String?
    let size: CGFloat

    var body: some View {
        Group {
            if let image = ModelPhoto.image(for: modelID) {
                Image(nsImage: image).resizable().interpolation(.high).scaledToFit()
            } else {
                Image(systemName: "headphones")
                    .font(.system(size: size * 0.55, weight: .regular))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

private struct DemoBadge: View {
    var body: some View {
        Text("Demo")
            .font(.system(size: 9, weight: .semibold))
            .padding(.horizontal, 5).padding(.vertical, 1)
            .overlay(Capsule().strokeBorder(.secondary, lineWidth: 1))
            .foregroundStyle(.secondary)
            .accessibilityLabel("Demo mode: simulated headphones, invented data.")
    }
}

struct BatteryLabel: View {
    let snapshot: WidgetSnapshot?
    let font: Font

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: symbol).font(font.weight(.regular)).imageScale(.small)
            if let level = snapshot?.batteryLevel {
                Text(verbatim: "\(level)%").font(font).monospacedDigit()
            } else {
                Text("—").font(font)
            }
        }
        .foregroundStyle(snapshot?.isLive == true ? .primary : .secondary)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibility)
    }

    private var symbol: String {
        guard let level = snapshot?.batteryLevel else { return "battery.0percent" }
        if snapshot?.isCharging == true { return "battery.100percent.bolt" }
        switch level {
        case 88...: return "battery.100percent"
        case 63..<88: return "battery.75percent"
        case 38..<63: return "battery.50percent"
        case 13..<38: return "battery.25percent"
        default: return "battery.0percent"
        }
    }

    private var accessibility: Text {
        guard let level = snapshot?.batteryLevel else { return Text("Battery level not reported") }
        return snapshot?.isCharging == true ? Text("Battery \(level)%, charging") : Text("Battery \(level)%")
    }
}

/// Live / last known / connecting, or why the last tap didn't work.
struct StatusLine: View {
    let entry: HeadphonesEntry
    @Environment(\.palette) private var palette

    var body: some View {
        HStack(spacing: 4) {
            if let failure = entry.failure {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(palette.warning)
                Text(Self.message(for: failure)).foregroundStyle(palette.warning)
            } else if !entry.pending.isEmpty {
                Image(systemName: "ellipsis")
                Text("Applying…")
            } else {
                linkStatus
            }
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .lineLimit(2)
    }

    @ViewBuilder private var linkStatus: some View {
        let snapshot = entry.snapshot
        switch snapshot?.link {
        case .live?:
            Circle().fill(palette.live).frame(width: 6, height: 6)
            Text("Live")
        case .connecting?:
            Text("Connecting…")
        case nil:
            Text("Open XM Audio Studio to connect.")
        case .released?, .offline?, .appClosed?:
            if let observed = snapshot?.observedAt, snapshot?.hasDeviceState == true {
                Text("Last known · \(Text(observed, style: .relative)) ago")
            } else {
                Text("Not connected")
            }
        }
    }

    static func message(for result: CommandOutcome.Result) -> LocalizedStringKey {
        switch result {
        case .unreachable: return "Couldn't reach the headphones."
        case .anotherDevice: return "Another device is using the headphones."
        case .stateUnavailable: return "The headphones didn't report their state."
        case .blockedByListeningMode: return "The EQ is off while Background Music or Cinema is on."
        case .expired: return "XM Audio Studio wasn't running. Try again."
        case .timedOut: return "The headphones didn't answer in time."
        case .sent, .alreadySet: return ""
        }
    }
}

struct NoiseModeLabel: View {
    let snapshot: WidgetSnapshot?
    let pending: [WidgetAction]

    var body: some View {
        let mode = pendingMode ?? snapshot?.noiseMode
        HStack(spacing: 4) {
            if let mode {
                Image(systemName: mode.icon)
                Text(mode.title)
            } else {
                Text("Noise control unknown")
            }
        }
        .font(.callout.weight(.medium))
        .foregroundStyle(snapshot?.isLive == true ? .primary : .secondary)
        .lineLimit(1)
        .minimumScaleFactor(0.8)
    }

    private var pendingMode: NoiseMode? {
        for case .setNoiseMode(let mode) in pending { return mode }
        return nil
    }
}

private struct NoiseButtons: View {
    let entry: HeadphonesEntry
    let circle: CGFloat
    @Environment(\.palette) private var palette

    var body: some View {
        HStack(spacing: 0) {
            ForEach(NoiseMode.allCases, id: \.self) { mode in
                let action = WidgetAction.setNoiseMode(mode)
                let pending = entry.isPending(action)
                let selected = pendingNoise == nil && entry.snapshot?.noiseMode == mode
                Button(intent: SetNoiseModeIntent(mode: NoiseModeOption(mode))) {
                    VStack(spacing: 4) {
                        Image(systemName: mode.icon)
                            .font(.system(size: circle * 0.38, weight: selected ? .semibold : .regular))
                            .foregroundStyle(selected ? Color.white : Color.primary.opacity(0.75))
                            .frame(width: circle, height: circle)
                            .background(Circle().fill(selected ? AnyShapeStyle(palette.accent) : AnyShapeStyle(palette.well)))
                            .overlay(Circle().strokeBorder(pending ? palette.accent : (selected ? .clear : palette.wellEdge),
                                                           style: StrokeStyle(lineWidth: pending ? 2 : 1, dash: pending ? [3, 3] : [])))
                            .widgetAccentable(selected)
                        Text(mode.shortTitle)
                            .font(.caption2.weight(selected ? .semibold : .regular))
                            .foregroundStyle(selected ? .primary : .secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(mode.title))
                .accessibilityAddTraits(selected ? [.isSelected] : [])
            }
        }
    }

    private var pendingNoise: NoiseMode? {
        for case .setNoiseMode(let mode) in entry.pending { return mode }
        return nil
    }
}

private struct EqPresetGrid: View {
    let entry: HeadphonesEntry
    @Environment(\.palette) private var palette

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 3)

    var body: some View {
        LazyVGrid(columns: columns, spacing: 6) {
            ForEach(EqPresetValue.allCases, id: \.self) { preset in
                let pending = entry.isPending(.applyEqPreset(preset))
                let selected = pendingPreset == nil && entry.snapshot?.eqPresetCode == preset.code
                Button(intent: ApplyEqPresetIntent(preset: EqPresetOption(preset))) {
                    Text(LocalizedStringKey(preset.label))
                        .font(.caption.weight(selected ? .semibold : .regular))
                        .foregroundStyle(selected ? Color.white : Color.primary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity, minHeight: 26)
                        .background(RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(selected ? AnyShapeStyle(palette.accent) : AnyShapeStyle(palette.well)))
                        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .strokeBorder(pending ? palette.accent : (selected ? .clear : palette.wellEdge),
                                          style: StrokeStyle(lineWidth: pending ? 2 : 1, dash: pending ? [3, 3] : [])))
                        .widgetAccentable(selected)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isSelected] : [])
            }
        }
    }

    private var pendingPreset: EqPresetValue? {
        for case .applyEqPreset(let preset) in entry.pending { return preset }
        return nil
    }
}

// MARK: - Labels

extension NoiseMode {
    var icon: String {
        switch self {
        case .noiseCancelling: return "person.wave.2.fill"
        case .ambientSound: return "waveform.and.person.filled"
        case .off: return "xmark"
        }
    }

    var title: LocalizedStringKey {
        switch self {
        case .noiseCancelling: return "Noise Canceling"
        case .ambientSound: return "Ambient Sound"
        case .off: return "Off"
        }
    }

    var shortTitle: LocalizedStringKey {
        switch self {
        case .noiseCancelling: return "NC"
        case .ambientSound: return "Ambient"
        case .off: return "Off"
        }
    }
}

extension EqPresetValue {
    /// Matches `EqualizerPreset.label` (localization keys shared with the app).
    var label: String {
        switch self {
        case .off: return "Off"
        case .heavy: return "Heavy"
        case .clear: return "Clear"
        case .hard: return "Hard"
        case .soft: return "Soft"
        case .custom: return "Custom"
        }
    }
}

private struct PaletteKey: EnvironmentKey {
    static let defaultValue = WidgetPalette(scheme: .light)
}

extension EnvironmentValues {
    var palette: WidgetPalette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}
