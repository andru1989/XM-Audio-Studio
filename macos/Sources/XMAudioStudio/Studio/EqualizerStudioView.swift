import SwiftUI
import AppKit
import UniformTypeIdentifiers
import SonyHeadphonesKit
import AudioStudioKit

private let chartGrid = FrequencyGrid.logSpaced(min: 20, max: 20_000, count: 200)

private func predicted(_ spec: EqualizerSpec, _ steps: [Int]) -> [Double] {
    AutoEqImporter.deviceModel(spec).responseDb(steps: steps, at: chartGrid)
}

/// The equalizer studio window: which EQ is being edited, the predicted response, the
/// band faders, and the profile library. Everything here edits the headphones' own EQ
/// (blue); the local DSP mode (amber) is announced but not built yet (ADR-0008).
struct EqualizerStudioView: View {
    @EnvironmentObject private var controller: HeadphonesController
    @EnvironmentObject private var studio: EqStudioModel

    @State private var namePrompt: NamePrompt?
    @State private var showAutoEqInput = false

    var body: some View {
        HSplitView {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    destinationBanner
                    if controller.connectionState != .connected && controller.equalizer == nil {
                        notConnected
                    } else {
                        editor
                    }
                }
                .padding(18)
            }
            .frame(minWidth: 560, maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Surface.window)

            ProfileSidebar(namePrompt: $namePrompt, showAutoEqInput: $showAutoEqInput)
                .frame(minWidth: 240, idealWidth: 270, maxWidth: 340)
        }
        .frame(minWidth: 820, minHeight: 600)
        .background(ControlSurfaceObserver(controller: controller))
        .sheet(item: $namePrompt) { prompt in
            NamePromptSheet(prompt: prompt) { name in
                switch prompt.kind {
                case .saveAs: studio.saveCurrent(as: name)
                case .duplicate(let p): studio.duplicate(p, as: name)
                case .rename(let p): studio.rename(p, to: name)
                }
            }
        }
        .sheet(isPresented: $showAutoEqInput) {
            AutoEqInputSheet { text, name in studio.previewAutoEq(text: text, name: name) }
        }
        .sheet(item: $studio.autoEqPreview) { preview in
            AutoEqPreviewSheet(preview: preview, spec: studio.spec)
        }
        .alert(studio.message ?? "", isPresented: Binding(get: { studio.message != nil }, set: { if !$0 { studio.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    // MARK: - Header

    private var destinationBanner: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Circle().fill(StudioColor.deviceEq).frame(width: 9, height: 9)
                Text("Headphone EQ")
                    .font(.headline)
                Text("Stored in the \(studio.model.displayName). Works on every device you pair them with.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 8) {
                Circle().fill(StudioColor.dspEq.opacity(0.45)).frame(width: 9, height: 9)
                Text("Advanced EQ (this Mac's DSP): planned, not active. Nothing is processed on this Mac.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
    }

    private var notConnected: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("The headphones' equalizer appears here once XM Audio Studio has read it.")
                .foregroundStyle(.secondary)
            Button("Connect") {
                controller.autoConnect()
            }
        }
    }

    // MARK: - Editor

    private var editor: some View {
        let spec = studio.spec
        let bands = studio.bands
        let editable = controller.equalizer != nil && !studio.listeningModeBlocksEq
        let target = studio.activeProfile?.sourceCurve.map { $0.parametric.responseDb(at: chartGrid) }
        var curves: [ChartCurve] = []
        if let target { curves.append(ChartCurve(frequencies: chartGrid, gainsDb: target, color: StudioColor.target, dashed: false, lineWidth: 1.6)) }
        if let other = studio.compareWith?.bands, other.count == spec.bandCount {
            curves.append(ChartCurve(frequencies: chartGrid, gainsDb: predicted(spec, other), color: .secondary.opacity(0.55), dashed: true, lineWidth: 1.4))
        }
        curves.append(ChartCurve(frequencies: chartGrid, gainsDb: predicted(spec, bands), color: StudioColor.deviceEq, dashed: true, lineWidth: 2.2))

        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(studio.activeProfile?.name ?? NSLocalizedString("Custom (unsaved)", comment: ""))
                    .font(.title2.weight(.semibold))
                if let category = studio.activeProfile?.category {
                    CategoryBadge(category: category)
                }
                Spacer()
            }

            if studio.deviceCurveNotInLibrary {
                HStack(spacing: 10) {
                    Image(systemName: "square.and.arrow.down.on.square")
                        .foregroundStyle(StudioColor.deviceEq)
                    Text("Your headphones have a curve that is not in your library yet.")
                        .font(.callout)
                    Spacer()
                    Button("Save to Library…") {
                        namePrompt = NamePrompt(kind: .saveAs, initial: studio.suggestedImportName)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }
                .padding(10)
                .cardSurface()
            }

            ResponseChart(curves: curves, bandFrequencies: spec.bandFrequenciesHz, bandGainsDb: bands.map { Double($0) * spec.stepDb })
                .frame(height: 190)
                .padding(10)
                .cardSurface()

            HStack(spacing: 14) {
                legend(color: StudioColor.deviceEq, dashed: true, "Predicted headphone curve")
                if target != nil { legend(color: StudioColor.target, dashed: false, "AutoEQ target") }
                if studio.compareWith?.bands != nil { legend(color: .secondary, dashed: true, "Previous curve (A/B)") }
            }
            .font(.caption)
            Text(spec.frequenciesVerified
                 ? "Computed from a model of Sony's EQ: a prediction, not a measurement of your headphones."
                 : "Computed from a model of Sony's EQ: a prediction, not a measurement. Band frequencies are labels not yet confirmed by the headphones.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            HStack(alignment: .bottom, spacing: 0) {
                ForEach(Array(spec.bandFrequenciesHz.enumerated()), id: \.offset) { index, frequency in
                    VStack(spacing: 4) {
                        Text(bands[index] > 0 ? "+\(bands[index])" : "\(bands[index])")
                            .font(.caption.monospacedDigit().weight(.medium))
                            .foregroundStyle(bands[index] == 0 ? .tertiary : .primary)
                        VerticalFader(value: bands[index], range: spec.minStep...spec.maxStep) { step in
                            studio.setBand(index, to: step)
                        }
                        .frame(width: 28, height: 132)
                        .disabled(!editable)
                        .accessibilityLabel("\(ResponseChart.frequencyLabel(frequency)) Hz")
                        .accessibilityValue("\(bands[index]) dB")
                        Text(ResponseChart.frequencyLabel(frequency))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .padding(.vertical, 6)

            warnings(spec: spec, bands: bands)

            HStack(spacing: 8) {
                Button("Flat") { studio.resetFlat() }
                    .disabled(!editable)
                Button { studio.undo() } label: { Label("Undo", systemImage: "arrow.uturn.backward") }
                    .keyboardShortcut("z", modifiers: .command)
                    .disabled(!editable || !studio.canUndo)
                Button { studio.toggleCompare() } label: { Label("A/B", systemImage: "arrow.left.arrow.right") }
                    .help("Swap between the current curve and the previous one")
                    .disabled(!editable || studio.compareWith == nil)
                Spacer()
                Button("Save as…") { namePrompt = NamePrompt(kind: .saveAs, initial: "") }
                    .disabled(controller.equalizer == nil)
            }
        }
    }

    @ViewBuilder
    private func warnings(spec: EqualizerSpec, bands: [Int]) -> some View {
        if studio.listeningModeBlocksEq {
            Label("The firmware turns the EQ off while the listening mode is Background Music or Cinema.", systemImage: "exclamationmark.triangle")
                .font(.caption)
                .foregroundStyle(StudioColor.warning)
        } else if case .preset(let code) = studio.current, code != EqualizerPreset.off.rawValue {
            let name = spec.presets.first { $0.code == code }?.name ?? String(format: "0x%02x", code)
            Text("Sony preset \(name) is active. Moving a band switches the headphones to a custom curve.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        let boost = Int(Double(bands.max() ?? 0) * spec.stepDb)
        if boost >= 4 {
            Label("A +\(boost) dB boost can clip with heavily compressed music.", systemImage: "waveform.badge.exclamationmark")
                .font(.caption)
                .foregroundStyle(StudioColor.warning)
        }
    }

    private func legend(color: Color, dashed: Bool, _ title: LocalizedStringKey) -> some View {
        HStack(spacing: 5) {
            Canvas { context, size in
                var p = Path()
                p.move(to: CGPoint(x: 0, y: size.height / 2))
                p.addLine(to: CGPoint(x: size.width, y: size.height / 2))
                context.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 2, dash: dashed ? [4, 3] : []))
            }
            .frame(width: 18, height: 8)
            Text(title).foregroundStyle(.secondary)
        }
    }
}

// MARK: - Profiles

struct CategoryBadge: View {
    let category: ProfileCategory

    var body: some View {
        Text(title)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill(color.opacity(0.18)))
            .foregroundStyle(color)
    }

    private var title: LocalizedStringKey {
        switch category {
        case .devicePreset: return "Sony preset"
        case .measured: return "Measured"
        case .subjective: return "Subjective"
        case .user: return "Mine"
        }
    }

    private var color: Color {
        switch category {
        case .devicePreset: return .secondary
        case .measured: return StudioColor.target
        case .subjective: return StudioColor.warning
        case .user: return StudioColor.deviceEq
        }
    }
}

private struct ProfileSidebar: View {
    @EnvironmentObject private var controller: HeadphonesController
    @EnvironmentObject private var studio: EqStudioModel
    @Binding var namePrompt: NamePrompt?
    @Binding var showAutoEqInput: Bool

    var body: some View {
        VStack(spacing: 0) {
            List {
                ForEach(ProfileCategory.allCases, id: \.self) { category in
                    let items = studio.profiles.filter { $0.category == category }
                    if !items.isEmpty {
                        Section {
                            ForEach(items) { profile in row(profile) }
                        } header: {
                            CategoryBadge(category: category)
                        }
                    }
                }
            }
            .listStyle(.sidebar)
            .scrollContentBackground(.hidden)

            Divider()
            VStack(alignment: .leading, spacing: 6) {
                Button { showAutoEqInput = true } label: { Label("Import AutoEQ…", systemImage: "waveform.path.ecg") }
                HStack {
                    Button("Export…", action: exportProfiles)
                    Button("Import…", action: importProfiles)
                }
                Text("Profiles live on this Mac. The headphones only hold the curve currently sent to them.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .controlSize(.small)
            .padding(10)
        }
        .background(Surface.card)
    }

    private func row(_ profile: EqProfile) -> some View {
        let active = profile.id == studio.activeProfile?.id
        return Button {
            studio.apply(profile)
        } label: {
            HStack {
                Image(systemName: active ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(active ? StudioColor.deviceEq : Color.secondary.opacity(0.4))
                VStack(alignment: .leading, spacing: 1) {
                    Text(profile.name)
                    if let notes = profile.notes {
                        Text(notes).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(studio.listeningModeBlocksEq)
        .contextMenu {
            Button("Apply") { studio.apply(profile) }
            Button("Duplicate…") { namePrompt = NamePrompt(kind: .duplicate(profile), initial: profile.name + " 2") }
            if !profile.isBuiltin {
                Button("Rename…") { namePrompt = NamePrompt(kind: .rename(profile), initial: profile.name) }
                if profile.deviceEq.steps != nil {
                    Button("Save current curve here") { studio.overwriteWithCurrent(profile) }
                }
                Divider()
                Button("Delete", role: .destructive) { studio.delete(profile) }
            }
        }
    }

    private func exportProfiles() {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.json]
        panel.nameFieldStringValue = "xm-audio-studio-profiles.json"
        guard panel.runModal() == .OK, let url = panel.url, let data = studio.exportData() else { return }
        do { try data.write(to: url, options: .atomic) } catch { studio.message = error.localizedDescription }
    }

    private func importProfiles() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do { studio.importProfiles(try Data(contentsOf: url)) } catch { studio.message = error.localizedDescription }
    }
}

// MARK: - Sheets

struct NamePrompt: Identifiable {
    enum Kind {
        case saveAs
        case duplicate(EqProfile)
        case rename(EqProfile)
    }

    let id = UUID()
    let kind: Kind
    let initial: String
}

private struct NamePromptSheet: View {
    let prompt: NamePrompt
    let onSave: (String) -> Void
    @State private var name = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Profile name").font(.headline)
            TextField("Name", text: $name)
                .textFieldStyle(.roundedBorder)
                .frame(width: 280)
                .onSubmit(save)
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("Save", action: save)
                    .keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
        .onAppear { name = prompt.initial }
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        onSave(String(trimmed.prefix(ProfileCodec.maxNameLength)))
        dismiss()
    }
}

private struct AutoEqInputSheet: View {
    let onFit: (String, String) -> Void
    @State private var text = ""
    @State private var name = "AutoEQ"
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Import AutoEQ").font(.headline)
            Text("Paste the ParametricEQ.txt or GraphicEQ.txt that AutoEq publishes for your model, or open the file. The curve is fitted to the headphones' own bands; nothing is sent until you choose to.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            TextField("Name", text: $name).textFieldStyle(.roundedBorder)
            TextEditor(text: $text)
                .font(.system(.caption, design: .monospaced))
                .frame(minHeight: 180)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.secondary.opacity(0.3)))
            HStack {
                Button("Open File…") {
                    let panel = NSOpenPanel()
                    panel.allowedContentTypes = [.plainText, .text]
                    if panel.runModal() == .OK, let url = panel.url, let content = try? String(contentsOf: url, encoding: .utf8) {
                        text = content
                        if name == "AutoEQ" { name = url.deletingPathExtension().lastPathComponent }
                    }
                }
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("Fit") {
                    onFit(text, name)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 520)
    }
}

private struct AutoEqPreviewSheet: View {
    let preview: AutoEqPreview
    let spec: EqualizerSpec
    @EnvironmentObject private var studio: EqStudioModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        let fit = preview.fit
        // The target is shifted by the free level offset so both curves share a reference.
        let target = fit.targetDb.map { $0 - fit.levelOffsetDb }
        VStack(alignment: .leading, spacing: 10) {
            Text("AutoEQ preview").font(.headline)
            TextField("Name", text: $name).textFieldStyle(.roundedBorder)
            ResponseChart(
                curves: [
                    ChartCurve(frequencies: fit.frequencies, gainsDb: target, color: StudioColor.target, dashed: false, lineWidth: 1.6),
                    ChartCurve(frequencies: fit.frequencies, gainsDb: fit.predictedDb, color: StudioColor.deviceEq, dashed: true, lineWidth: 2.2),
                ],
                bandFrequencies: spec.bandFrequenciesHz,
                bandGainsDb: fit.steps.map { Double($0) * spec.stepDb }
            )
            .frame(height: 200)
            Text(fit.steps.map { $0 > 0 ? "+\($0)" : "\($0)" }.joined(separator: "  "))
                .font(.body.monospacedDigit())
            Text(String(format: NSLocalizedString("Weighted RMS error %.2f dB · largest %.1f dB (20 Hz–10 kHz)", comment: ""),
                        fit.weightedRmsErrorDb, fit.maxAbsErrorDb))
                .font(.caption)
            Text("The headphones' fixed bands cannot reproduce a parametric EQ exactly. Purple is the target, blue the predicted result.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) {
                    studio.autoEqPreview = nil
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)
                Button("Save") { save(apply: false) }
                Button("Save and Apply") { save(apply: true) }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 560)
        .onAppear { name = preview.name }
    }

    private func save(apply: Bool) {
        var named = preview
        named.name = name.trimmingCharacters(in: .whitespaces).isEmpty ? preview.name : name
        studio.saveAutoEq(named, apply: apply)
        dismiss()
    }
}
