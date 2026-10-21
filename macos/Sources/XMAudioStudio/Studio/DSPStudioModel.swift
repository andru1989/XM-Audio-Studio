import Foundation
import AppKit
import Combine
import os
import AudioStudioKit
import AudioDSPKit

/// The headphone EQ (blue) and the local DSP (amber) applying the same AutoEQ
/// correction at once doubles it.
enum CorrectionConflict: Equatable {
    case none
    /// The DSP holds an AutoEQ correction and the headphones' EQ could not be read.
    case headphoneEqUnknown(dsp: DSPCorrectionSource)
    case doubled(headphoneProfile: EqProfile, dsp: DSPCorrectionSource)

    var key: String? {
        if case .doubled(let headphone, let dsp) = self { return "\(headphone.id)|\(dsp.profileId)" }
        return nil
    }
}

/// State of the "Advanced EQ" (local DSP on this Mac, amber). Independent of the
/// headphones: nothing here talks Bluetooth, and the headphones' EQ is never changed
/// from here except by the explicit "turn off headphone EQ" action in the UI, which
/// goes through `EqStudioModel`. The DSP is off at every launch; bands, preamp and the
/// chosen output are remembered.
@MainActor
final class DSPStudioModel: ObservableObject {
    @Published private(set) var settings: DSPSettings
    @Published private(set) var outputUID: String?
    @Published private(set) var devices: [AudioOutputDevice] = []
    @Published private(set) var systemOutputUID: String?
    @Published private(set) var state: DSPEngineState = .off
    /// The user wants the DSP on (running, or waiting for the output to come back).
    @Published private(set) var isEnabled = false
    /// Input peak of the last quarter second while running, in dBFS (nil: no signal).
    @Published private(set) var inputLevelDb: Double?
    /// Seconds without any input signal while running (permission/launch diagnostics).
    @Published private(set) var silentSeconds = 0.0

    private static let settingsKey = "dsp.settings"
    private static let outputKey = "dsp.outputUID"
    /// Troubleshooting only: "systemWide" switches to ParaEQ's all-apps tap (docs/dsp/DSP-ENGINE.md).
    private static let scopeKey = "dsp.tapScope"
    static let previewSampleRate = 48_000.0

    private let engine: ProcessTapEngine
    private let watcher = AudioDeviceWatcher()
    private var acceptedConflicts: Set<String> = []
    private var levelTimer: Timer?
    private var terminationObserver: NSObjectProtocol?
    private let logger = Logger(subsystem: "io.github.andru1989.xmaudiostudio", category: "dsp")

    init() {
        let scope = DSPTapScope(rawValue: UserDefaults.standard.string(forKey: Self.scopeKey) ?? "") ?? .selectedDevice
        engine = ProcessTapEngine(scope: scope)
        var saved = (UserDefaults.standard.data(forKey: Self.settingsKey))
            .flatMap { try? JSONDecoder().decode(DSPSettings.self, from: $0) } ?? .flat
        saved.isBypassed = false // A/B is a momentary comparison, not a saved state.
        settings = saved.sanitized()
        outputUID = UserDefaults.standard.string(forKey: Self.outputKey)

        engine.log = { [logger] message in logger.info("\(message, privacy: .public)") }
        engine.onStateChange = { [weak self] state in self?.engineStateChanged(state) }
        watcher.onChange = { [weak self] in self?.refreshDevices() }
        refreshDevices()
        terminationObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.willTerminateNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.engine.stop() }
        }
    }

    // MARK: - Devices

    func refreshDevices() {
        devices = AudioOutputDevices.outputDevices()
        systemOutputUID = AudioOutputDevices.defaultOutputDevice()?.uid
        if outputUID == nil {
            // First run: suggest Sony headphones when connected, otherwise the current
            // system output. Only a suggestion: nothing runs until the DSP is turned on.
            // A remembered output that is disconnected stays selected (shown as such).
            let sony = devices.first { $0.isBluetooth && $0.name.localizedCaseInsensitiveContains("WH-1000XM") }
            outputUID = sony?.uid ?? systemOutputUID ?? devices.first?.uid
        }
    }

    var selectedDevice: AudioOutputDevice? { devices.first { $0.uid == outputUID } }

    var selectedIsSystemOutput: Bool { outputUID != nil && outputUID == systemOutputUID }

    func selectOutput(_ uid: String) {
        guard uid != outputUID else { return }
        outputUID = uid
        UserDefaults.standard.set(uid, forKey: Self.outputKey)
        if isEnabled { engine.start(outputUID: uid, settings: settings) }
    }

    // MARK: - On / off

    func setEnabled(_ on: Bool) {
        if on {
            guard let uid = outputUID else { return }
            UserDefaults.standard.set(uid, forKey: Self.outputKey)
            isEnabled = true
            engine.start(outputUID: uid, settings: settings)
        } else {
            engine.stop()
        }
    }

    private func engineStateChanged(_ new: DSPEngineState) {
        state = new
        switch new {
        case .running, .waiting:
            isEnabled = true
        case .off, .failed:
            isEnabled = false
        }
        if case .running = new { startLevelTimer() } else { stopLevelTimer() }
    }

    private func startLevelTimer() {
        guard levelTimer == nil else { return }
        silentSeconds = 0
        levelTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.sampleLevel() }
        }
    }

    private func stopLevelTimer() {
        levelTimer?.invalidate()
        levelTimer = nil
        inputLevelDb = nil
        silentSeconds = 0
    }

    private func sampleLevel() {
        guard let peak = engine.takeInputPeak() else { return }
        if peak > 1e-5 {
            inputLevelDb = 20 * log10(Double(peak))
            silentSeconds = 0
        } else {
            inputLevelDb = nil
            silentSeconds += 0.25
        }
    }

    // MARK: - Settings

    /// What the chart and the preamp read show: the running coefficients, or the same
    /// maths at 48 kHz stereo while off.
    var plan: DSPCoefficientPlan {
        engine.runningPlan ?? DSPCoefficientPlan(settings: settings, sampleRate: Self.previewSampleRate, channelCount: 2)
    }

    private func change(_ transform: (inout DSPSettings) -> Void) {
        var next = settings
        transform(&next)
        next = next.sanitized()
        guard next != settings else { return }
        settings = next
        var stored = next
        stored.isBypassed = false
        if let data = try? JSONEncoder().encode(stored) { UserDefaults.standard.set(data, forKey: Self.settingsKey) }
        engine.update(settings: next)
    }

    var canAddBand: Bool { settings.bands.count < DSPSettings.maxBands }

    func addBand() {
        guard canAddBand else { return }
        // Start where the existing bands leave the widest gap (log scale), at 0 dB.
        let used = settings.bands.map { min(max($0.filter.frequency, 20), 20_000) }.sorted()
        var frequency = 1_000.0
        if !used.isEmpty {
            let edges = [20.0] + used + [20_000.0]
            var widest = -1.0
            for i in 0..<(edges.count - 1) where log10(edges[i + 1] / edges[i]) > widest {
                widest = log10(edges[i + 1] / edges[i])
                frequency = (edges[i] * edges[i + 1]).squareRoot()
            }
        }
        change { $0.bands.append(DSPBand(.peaking, frequency: frequency.rounded(), gainDb: 0, q: 1.0)) }
    }

    func band(_ id: UUID) -> DSPBand? { settings.bands.first { $0.id == id } }

    func updateBand(_ band: DSPBand) {
        change { s in
            if let i = s.bands.firstIndex(where: { $0.id == band.id }) { s.bands[i] = band }
        }
    }

    func removeBand(_ id: UUID) {
        change { $0.bands.removeAll { $0.id == id } }
    }

    func clearBands() {
        change {
            $0.bands = []
            $0.correctionSource = nil
        }
    }

    func setPreamp(_ db: Double) { change { $0.preampDb = (db * 2).rounded() / 2 } }
    func setAutoHeadroom(_ on: Bool) { change { $0.autoHeadroom = on } }
    func setBypassed(_ on: Bool) { change { $0.isBypassed = on } }

    /// Replaces the bands with the parametric AutoEQ curve stored in a measured profile.
    func applyCurve(of profile: EqProfile) {
        guard let curve = profile.sourceCurve else { return }
        let source = DSPCorrectionSource(profileId: profile.id, profileName: profile.name)
        change { s in
            let fresh = DSPSettings.from(curve: curve.parametric, source: source, autoHeadroom: s.autoHeadroom)
            s.bands = fresh.bands
            s.preampDb = fresh.preampDb
            s.correctionSource = source
            s.isBypassed = false
        }
    }

    // MARK: - Double correction

    func conflict(headphoneProfile: EqProfile?, headphoneEqKnown: Bool) -> CorrectionConflict {
        guard let dsp = settings.correctionSource else { return .none }
        if let headphoneProfile, headphoneProfile.category == .measured {
            return .doubled(headphoneProfile: headphoneProfile, dsp: dsp)
        }
        return headphoneEqKnown ? .none : .headphoneEqUnknown(dsp: dsp)
    }

    func isAccepted(_ conflict: CorrectionConflict) -> Bool {
        guard let key = conflict.key else { return true }
        return acceptedConflicts.contains(key)
    }

    func accept(_ conflict: CorrectionConflict) {
        if let key = conflict.key { acceptedConflicts.insert(key) }
    }
}
