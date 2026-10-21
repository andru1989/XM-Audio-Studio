import SwiftUI
import AppKit
import SonyHeadphonesKit
import AudioStudioKit
import AudioDSPKit

private let dspChartGrid = FrequencyGrid.logSpaced(min: 20, max: 20_000, count: 200)

/// What needs an explicit "yes" before it happens.
private struct DSPConfirmation: Identifiable {
    enum Action {
        case enable
        case applyCurve(EqProfile)
    }

    let id = UUID()
    let action: Action
    let headphoneProfile: EqProfile
    let correctionName: String
}

/// "Advanced EQ": the local DSP on this Mac (amber). Kept visually and functionally
/// apart from the headphone EQ (blue). Beta, optional, off by default.
struct DSPEqualizerView: View {
    @EnvironmentObject private var controller: HeadphonesController
    @EnvironmentObject private var studio: EqStudioModel
    @EnvironmentObject private var dsp: DSPStudioModel

    @State private var confirmation: DSPConfirmation?

    private var headphoneEqKnown: Bool { controller.equalizer != nil }
    private var conflict: CorrectionConflict {
        dsp.conflict(headphoneProfile: studio.activeProfile, headphoneEqKnown: headphoneEqKnown)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            conflictBanner
            chart
            levelControls
            bandList
            autoEqCard
            footnotes
        }
        .alert(
            Text("Double correction"),
            isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { confirmation = nil } }),
            presenting: confirmation
        ) { pending in
            if canTurnOffHeadphoneEq {
                Button("Turn Off Headphone EQ and Continue") {
                    turnOffHeadphoneEq()
                    perform(pending, accepted: false)
                }
            }
            Button("Continue Anyway", role: .destructive) {
                perform(pending, accepted: true)
            }
            Button("Cancel", role: .cancel) {}
        } message: { pending in
            Text("The headphones are already applying “\(pending.headphoneProfile.name)”, a measured AutoEQ correction fitted to their bands. Applying “\(pending.correctionName)” on this Mac as well corrects the same headphones twice: a +4 dB fix becomes about +8 dB. Turn off the headphone EQ first, or continue only if you mean to.")
        }
    }

    // MARK: - Header: on/off, output, status

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Circle().fill(StudioColor.dspEq).frame(width: 9, height: 9)
                Text("Advanced EQ (this Mac's DSP)")
                    .font(.headline)
                BetaBadge()
                Spacer()
                Toggle("Advanced EQ", isOn: Binding(get: { dsp.isEnabled }, set: setEnabled))
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .tint(StudioColor.dspEq)
                    .disabled(dsp.outputUID == nil)
                    .accessibilityLabel("Advanced EQ")
            }
            Text("Equalizes what this Mac plays on the output you choose, before it reaches the headphones. The headphone EQ (blue) is not changed. Off by default; macOS asks for the System Audio Recording permission the first time.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 8) {
                Text("Output")
                Picker("Output", selection: Binding(get: { dsp.outputUID ?? "" }, set: { dsp.selectOutput($0) })) {
                    ForEach(dsp.devices) { device in
                        Text(device.uid == dsp.systemOutputUID
                             ? String(format: NSLocalizedString("%@ (system output)", comment: ""), device.name)
                             : device.name)
                            .tag(device.uid)
                    }
                    if let uid = dsp.outputUID, dsp.selectedDevice == nil {
                        Text("Disconnected device").tag(uid)
                    }
                }
                .labelsHidden()
                .frame(maxWidth: 340)
                Button {
                    dsp.refreshDevices()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .buttonStyle(.borderless)
                .help("Refresh the device list")
                Spacer()
            }
            if dsp.selectedDevice != nil && !dsp.selectedIsSystemOutput {
                Label("Not the current system output: only apps that play directly to it are processed. XM Audio Studio never changes the system output.", systemImage: "info.circle")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            status
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
        .overlay(RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
            .strokeBorder(StudioColor.dspEq.opacity(dsp.isEnabled ? 0.8 : 0.35), lineWidth: 1))
    }

    @ViewBuilder
    private var status: some View {
        switch dsp.state {
        case .off:
            Label("Off. Nothing is processed on this Mac.", systemImage: "power")
                .font(.caption)
                .foregroundStyle(.secondary)
        case .running(let info):
            VStack(alignment: .leading, spacing: 4) {
                Label(runningText(info), systemImage: "waveform")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(StudioColor.dspEq)
                Text(levelText)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                if dsp.silentSeconds >= 5 {
                    Label("No audio is reaching the equalizer. If something is playing to this output, check that XM Audio Studio is allowed in System Settings › Privacy & Security › Screen & System Audio Recording.", systemImage: "questionmark.circle")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        case .waiting(let reason):
            Label(waitingText(reason), systemImage: "pause.circle")
                .font(.caption)
                .foregroundStyle(StudioColor.warning)
        case .failed(let error):
            VStack(alignment: .leading, spacing: 6) {
                Label(errorText(error), systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(StudioColor.warning)
                    .fixedSize(horizontal: false, vertical: true)
                if case .tapCreationFailed = error {
                    Button("Open Privacy Settings") {
                        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
                            NSWorkspace.shared.open(url)
                        }
                    }
                    .controlSize(.small)
                }
            }
        }
    }

    private func runningText(_ info: DSPRunningInfo) -> String {
        String(format: NSLocalizedString("Processing %@ · %@ kHz · %lld ch · +%@ ms", comment: ""),
               info.deviceName,
               (info.sampleRate / 1000).formatted(.number.precision(.fractionLength(0...1))),
               info.channelCount,
               info.latencyMs.formatted(.number.precision(.fractionLength(0...1))))
    }

    private var levelText: String {
        guard let level = dsp.inputLevelDb else { return NSLocalizedString("Input: no signal", comment: "") }
        return String(format: NSLocalizedString("Input peak %@ dBFS", comment: ""), level.formatted(.number.precision(.fractionLength(0))))
    }

    private func waitingText(_ reason: DSPWaitReason) -> String {
        switch reason {
        case .deviceUnavailable:
            return NSLocalizedString("Paused: the output is disconnected. Processing resumes when it comes back.", comment: "")
        case .notSystemOutput(let name):
            return String(format: NSLocalizedString("Paused: %@ is not the system output.", comment: ""), name)
        }
    }

    private func errorText(_ error: DSPEngineError) -> String {
        switch error {
        case .tapCreationFailed(let status):
            return String(format: NSLocalizedString("Couldn't capture this Mac's audio (error %ld). Allow XM Audio Studio in System Settings › Privacy & Security › Screen & System Audio Recording, then turn the advanced EQ on again.", comment: ""), Int(status))
        case .unsupportedFormat(let detail):
            return String(format: NSLocalizedString("This output's audio format is not supported (%@). Nothing is processed.", comment: ""), detail)
        case .coreAudio(let step, let status):
            return String(format: NSLocalizedString("Core Audio error %ld (%@). Nothing is processed.", comment: ""), Int(status), step)
        case .stalled:
            return NSLocalizedString("The audio device did not start. Turn the advanced EQ on again; if it keeps failing, wait a minute and retry.", comment: "")
        }
    }

    private func setEnabled(_ on: Bool) {
        guard on else {
            dsp.setEnabled(false)
            return
        }
        if case .doubled(let headphone, let source) = conflict, !dsp.isAccepted(conflict) {
            confirmation = DSPConfirmation(action: .enable, headphoneProfile: headphone, correctionName: source.profileName)
            return
        }
        dsp.setEnabled(true)
    }

    // MARK: - Double correction

    @ViewBuilder
    private var conflictBanner: some View {
        switch conflict {
        case .none:
            EmptyView()
        case .headphoneEqUnknown(let source):
            Label(String(format: NSLocalizedString("These bands are the AutoEQ correction “%@”. The headphones' EQ could not be read: make sure it is not applying the same correction.", comment: ""), source.profileName),
                  systemImage: "info.circle")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        case .doubled(let headphone, let source):
            VStack(alignment: .leading, spacing: 8) {
                Label("Double correction", systemImage: "exclamationmark.triangle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(StudioColor.warning)
                Text("The headphones are applying “\(headphone.name)” (measured) and these bands are “\(source.profileName)”. Both correct the same headphone response, so with the advanced EQ on the correction is doubled.")
                    .font(.caption)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    Button("Turn Off Headphone EQ") { turnOffHeadphoneEq() }
                        .disabled(!canTurnOffHeadphoneEq)
                    if dsp.isEnabled && !dsp.settings.isBypassed {
                        Button("Bypass Advanced EQ") { dsp.setBypassed(true) }
                    }
                }
                .controlSize(.small)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Radius.control, style: .continuous).fill(StudioColor.warning.opacity(0.10)))
            .overlay(RoundedRectangle(cornerRadius: Radius.control, style: .continuous).strokeBorder(StudioColor.warning.opacity(0.6), lineWidth: 1))
        }
    }

    private var sonyOriginal: EqProfile? { studio.profiles.first { $0.id == "builtin.sony-original" } }

    private var canTurnOffHeadphoneEq: Bool {
        controller.equalizer != nil && !studio.listeningModeBlocksEq
    }

    /// Sets the headphones to "Original Sony" (their EQ off), through the normal
    /// studio path (undoable, same command as picking it in the library).
    private func turnOffHeadphoneEq() {
        if let original = sonyOriginal { studio.apply(original) } else { studio.resetFlat() }
    }

    private func perform(_ pending: DSPConfirmation, accepted: Bool) {
        switch pending.action {
        case .enable:
            break
        case .applyCurve(let profile):
            dsp.applyCurve(of: profile)
        }
        if accepted {
            dsp.accept(dsp.conflict(headphoneProfile: pending.headphoneProfile, headphoneEqKnown: true))
        }
        if case .enable = pending.action { dsp.setEnabled(true) }
    }

    // MARK: - Chart

    private var chart: some View {
        let plan = dsp.plan
        let left = plan.responseDb(at: dspChartGrid, channel: 0)
        let right = plan.responseDb(at: dspChartGrid, channel: 1)
        let preamp = plan.preampDb
        let net = left.map { $0 + preamp }
        let enabled = dsp.settings.bands.filter(\.isEnabled)
        let peak = max(left.map(abs).max() ?? 0, right.map(abs).max() ?? 0, net.map(abs).max() ?? 0)
        var curves = [
            ChartCurve(frequencies: dspChartGrid, gainsDb: net, color: StudioColor.dspEq.opacity(0.45), dashed: true, lineWidth: 1.4),
            ChartCurve(frequencies: dspChartGrid, gainsDb: left, color: StudioColor.dspEq, dashed: false, lineWidth: 2.2),
        ]
        if right != left {
            curves.append(ChartCurve(frequencies: dspChartGrid, gainsDb: right, color: StudioColor.dspEq.opacity(0.7), dashed: true, lineWidth: 1.8))
        }
        return VStack(alignment: .leading, spacing: 6) {
            ResponseChart(curves: curves,
                          bandFrequencies: enabled.map(\.filter.frequency),
                          bandGainsDb: enabled.map(\.filter.gainDb),
                          rangeDb: peak > 12 ? 24 : 12,
                          markerColor: StudioColor.dspEq)
                .frame(height: 190)
                .padding(10)
                .cardSurface()
            HStack(spacing: 14) {
                legend(color: StudioColor.dspEq, dashed: false, right != left ? "Left channel (exact)" : "Advanced EQ curve (exact)")
                if right != left { legend(color: StudioColor.dspEq.opacity(0.7), dashed: true, "Right channel") }
                legend(color: StudioColor.dspEq.opacity(0.45), dashed: true, "Including preamp")
            }
            .font(.caption)
            Text("Computed from the same coefficients the DSP runs, so this is the curve applied to the audio (on top of the headphones' own sound and EQ).")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
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

    // MARK: - Preamp, headroom, A/B

    private var levelControls: some View {
        let plan = dsp.plan
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text("Preamp")
                    .fixedSize()
                Slider(value: Binding(get: { dsp.settings.autoHeadroom ? plan.preampDb : dsp.settings.preampDb },
                                      set: { dsp.setPreamp($0) }),
                       in: -24...6)
                    .frame(minWidth: 120, maxWidth: 220)
                    .tint(StudioColor.dspEq)
                    .disabled(dsp.settings.autoHeadroom)
                Text(signedDb(plan.preampDb))
                    .font(.caption.monospacedDigit())
                    .frame(width: 58, alignment: .trailing)
                Toggle("Automatic headroom", isOn: Binding(get: { dsp.settings.autoHeadroom }, set: { dsp.setAutoHeadroom($0) }))
                    .toggleStyle(.checkbox)
                    .fixedSize()
                    .help("Lowers the level by the curve's largest boost so the equalizer never pushes audio into clipping.")
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                Text("A/B")
                    .fixedSize()
                Picker("A/B", selection: Binding(get: { dsp.settings.isBypassed }, set: { dsp.setBypassed($0) })) {
                    Text("Equalized").tag(false)
                    Text("Original").tag(true)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
                .help("A/B: compare the equalized sound with the untouched original (bit-exact)")
                Text("Original is the untouched input, bit for bit.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                Spacer(minLength: 0)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: Radius.control)
    }

    private func signedDb(_ value: Double) -> String {
        let rounded = (value * 10).rounded() / 10
        let text = abs(rounded).formatted(.number.precision(.fractionLength(1)))
        return rounded > 0 ? "+\(text) dB" : (rounded < 0 ? "−\(text) dB" : "0.0 dB")
    }

    // MARK: - Bands

    private var bandList: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Bands").font(.headline)
                Text("\(dsp.settings.bands.count) of \(DSPSettings.maxBands)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                Spacer()
                Button { dsp.addBand() } label: { Label("Add Band", systemImage: "plus") }
                    .disabled(!dsp.canAddBand)
                Button("Clear Bands") { dsp.clearBands() }
                    .disabled(dsp.settings.bands.isEmpty)
            }
            .controlSize(.small)

            if dsp.settings.bands.isEmpty {
                Text("No bands: the audio passes through unchanged apart from the limiter. Add a band or apply an AutoEQ curve.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 6) {
                    GridRow {
                        Text("On")
                        Text("Type")
                        Text("Frequency")
                        Text("Gain")
                        Text("Q")
                        Text("Channels")
                        Text("")
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    ForEach(dsp.settings.bands) { band in
                        BandRow(band: band, onChange: { dsp.updateBand($0) }, onRemove: { dsp.removeBand(band.id) })
                    }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
    }

    // MARK: - AutoEQ

    private var autoEqCard: some View {
        let profile = studio.activeProfile
        let curve = profile?.sourceCurve
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "waveform.path.ecg").foregroundStyle(StudioColor.target)
                Text("AutoEQ target").font(.headline)
                Spacer()
                Button("Apply the Profile's AutoEQ Target Curve") {
                    guard let profile else { return }
                    applyCurve(profile)
                }
                .disabled(curve == nil)
            }
            if let profile, let curve {
                Text(String(format: NSLocalizedString("Uses the parametric curve stored in “%@” (%lld filters, preamp %@ dB) exactly, instead of the approximation the headphones' bands can make. It replaces the current bands.", comment: ""),
                            profile.name, curve.filters.count, curve.preampDb.formatted(.number.precision(.fractionLength(1)))))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Text("The active headphone profile has no AutoEQ curve. Profiles imported with “Import AutoEQ…” keep their parametric curve; apply one to the headphones, then use it here.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let source = dsp.settings.correctionSource {
                Text(String(format: NSLocalizedString("Current bands: AutoEQ “%@”.", comment: ""), source.profileName))
                    .font(.caption)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
    }

    private func applyCurve(_ profile: EqProfile) {
        // The headphones hold a measured correction: applying one here too doubles it.
        if let headphone = studio.activeProfile, headphone.category == .measured {
            confirmation = DSPConfirmation(action: .applyCurve(profile), headphoneProfile: headphone, correctionName: profile.name)
            return
        }
        dsp.applyCurve(of: profile)
    }

    private var footnotes: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Beta. Uses a Core Audio process tap (macOS 14.4 or later): no driver is installed and the system output is never changed. Turning it off, quitting or a crash returns the audio to normal, unprocessed.")
            Text("Adds about 5 ms of latency (limiter look-ahead) plus the device buffer; Bluetooth adds its own. A headset switching to call mode restarts processing at the new format.")
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .fixedSize(horizontal: false, vertical: true)
    }
}

// MARK: - Pieces

struct BetaBadge: View {
    var body: some View {
        Text("Beta")
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill(StudioColor.dspEq.opacity(0.18)))
            .foregroundStyle(StudioColor.dspEq)
    }
}

private struct BandRow: View {
    let band: DSPBand
    let onChange: (DSPBand) -> Void
    let onRemove: () -> Void

    private func binding<T>(_ keyPath: WritableKeyPath<DSPBand, T>) -> Binding<T> {
        Binding(get: { band[keyPath: keyPath] }, set: { value in
            var updated = band
            updated[keyPath: keyPath] = value
            onChange(updated)
        })
    }

    var body: some View {
        GridRow {
            Toggle("On", isOn: binding(\.isEnabled))
                .toggleStyle(.checkbox)
                .labelsHidden()
            Picker("Type", selection: binding(\.filter.type)) {
                Text("Peak").tag(FilterType.peaking)
                Text("Low shelf").tag(FilterType.lowShelf)
                Text("High shelf").tag(FilterType.highShelf)
            }
            .labelsHidden()
            .frame(width: 136)
            HStack(spacing: 3) {
                TextField("Hz", value: binding(\.filter.frequency), format: .number.precision(.fractionLength(0...1)))
                    .frame(width: 70)
                Text("Hz").foregroundStyle(.secondary)
            }
            HStack(spacing: 3) {
                TextField("dB", value: binding(\.filter.gainDb), format: .number.precision(.fractionLength(0...2)))
                    .frame(width: 56)
                Stepper("Gain", value: binding(\.filter.gainDb), in: DSPSettings.gainRangeDb, step: 0.5)
                    .labelsHidden()
                Text("dB").foregroundStyle(.secondary)
            }
            TextField("Q", value: binding(\.filter.q), format: .number.precision(.fractionLength(0...3)))
                .frame(width: 56)
            Picker("Channels", selection: binding(\.channels)) {
                Text("L+R").tag(DSPChannelScope.both)
                Text("L").tag(DSPChannelScope.left)
                Text("R").tag(DSPChannelScope.right)
            }
            .labelsHidden()
            .frame(width: 70)
            Button(action: onRemove) { Image(systemName: "minus.circle") }
                .buttonStyle(.borderless)
                .help("Remove band")
        }
        .textFieldStyle(.roundedBorder)
        .controlSize(.small)
        .opacity(band.isEnabled ? 1 : 0.5)
    }
}
