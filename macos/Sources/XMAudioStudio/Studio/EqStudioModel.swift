import Foundation
import Combine
import SonyHeadphonesKit
import AudioStudioKit

/// A curve the headphones can hold: a Sony preset or a custom band curve.
enum DeviceEqValue: Equatable {
    case preset(UInt8)
    case bands([Int])

    init?(_ setting: DeviceEqSetting) {
        if let code = setting.presetCode {
            self = .preset(UInt8(code))
        } else if let steps = setting.steps {
            self = .bands(steps)
        } else {
            return nil
        }
    }

    var bands: [Int]? {
        if case .bands(let b) = self { return b }
        return nil
    }
}

struct AutoEqPreview: Identifiable {
    let id = UUID()
    var name: String
    let fit: FitResult
    let sourceCurve: SourceCurve?
}

/// Equalizer-studio state shared by every window: the profile library, undo history,
/// the A/B slot and AutoEQ previews. Same behaviour as Android's `MainViewModel` EQ region.
@MainActor
final class EqStudioModel: ObservableObject {
    @Published private(set) var userProfiles: [EqProfile] = []
    @Published private(set) var canUndo = false
    @Published private(set) var compareWith: DeviceEqValue?
    @Published var autoEqPreview: AutoEqPreview?
    @Published var message: String?

    private let controller: HeadphonesController
    private let library: ProfileLibrary
    private var history: [DeviceEqValue] = []
    private var editOrigin: DeviceEqValue?
    private var editCommitTask: Task<Void, Never>?
    private static let maxHistory = 30
    private static let lastAppliedKey = "lastAppliedProfileId"

    init(controller: HeadphonesController, store: ProfileStore = .standard) {
        self.controller = controller
        library = ProfileLibrary(store: store)
        userProfiles = library.userProfiles
    }

    // MARK: - Device

    /// The model being controlled. The controller is XM6-only today; the name is a hint.
    var model: HeadphoneModel { HeadphoneModel.from(bluetoothName: controller.deviceName) ?? .wh1000xm6 }

    var spec: EqualizerSpec { DeviceCatalog.capabilities(model).equalizer ?? DeviceCatalog.xm6Equalizer }

    var current: DeviceEqValue? {
        guard let eq = controller.equalizer else { return nil }
        return eq.presetCode == EqualizerPreset.custom.rawValue ? .bands(eq.bands) : .preset(eq.presetCode)
    }

    /// Bands shown on the faders: the reported curve, or flat while none is known.
    var bands: [Int] {
        let reported = controller.equalizer?.bands ?? []
        return reported.count == spec.bandCount ? reported : Array(repeating: 0, count: spec.bandCount)
    }

    /// The firmware disables the EQ while BGM or Cinema is on.
    var listeningModeBlocksEq: Bool {
        guard let mode = controller.listeningMode else { return false }
        return mode != .standard
    }

    // MARK: - Profiles

    var profiles: [EqProfile] { BuiltinProfiles.forModel(model.rawValue) + userProfiles.filter { $0.model == model.rawValue } }

    private var lastAppliedId: String? {
        get { UserDefaults.standard.string(forKey: Self.lastAppliedKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.lastAppliedKey) }
    }

    /// Bands the headphones report for whatever is active: the custom curve, a Sony preset,
    /// or a user setting saved from Sound Connect.
    var reportedBands: [Int]? {
        let bands = controller.equalizer?.bands ?? []
        return bands.count == spec.bandCount ? bands : nil
    }

    /// A preset code this app does not know, e.g. a user setting (0xA1+) made in Sound Connect.
    var isUserPresetFromOfficialApp: Bool {
        guard case .preset(let code) = current else { return false }
        return !spec.presets.contains { $0.code == code }
    }

    /// The profile whose curve matches what the headphones report, preferring the one last
    /// applied. A Sound Connect user setting matches a saved profile with the same bands.
    var activeProfile: EqProfile? {
        guard let current else { return nil }
        let bands = reportedBands
        let matches = profiles.filter { profile in
            DeviceEqValue(profile.deviceEq) == current
                || (isUserPresetFromOfficialApp && bands != nil && profile.deviceEq.steps == bands)
        }
        return matches.first { $0.id == lastAppliedId } ?? matches.first
    }

    /// The headphones hold a custom curve (read from them, or set in Sound Connect) that is
    /// not in the library yet: offer to bring it into the app.
    var deviceCurveNotInLibrary: Bool {
        guard controller.connectionState == .connected, activeProfile == nil, reportedBands != nil else { return false }
        if case .bands = current { return true }
        return isUserPresetFromOfficialApp
    }

    var suggestedImportName: String {
        let date = DateFormatter.localizedString(from: Date(), dateStyle: .short, timeStyle: .none)
        return String(format: NSLocalizedString("Headphones curve %@", comment: ""), date)
    }

    // MARK: - Editing

    private func remember(_ previous: DeviceEqValue?) {
        guard let previous else { return }
        if history.last != previous { history.append(previous) }
        if history.count > Self.maxHistory { history.removeFirst(history.count - Self.maxHistory) }
        compareWith = previous
        canUndo = !history.isEmpty
    }

    private func send(_ value: DeviceEqValue) {
        switch value {
        case .preset(let code):
            if let preset = EqualizerPreset(rawValue: code) { controller.setEqualizerPreset(preset) }
        case .bands(let bands):
            controller.setEqualizerBands(bands)
        }
    }

    /// Applies the profile's EQ, and its scene if it has one (the views confirm a scene first,
    /// listing `sceneChanges(for:)`). Scene and EQ go out as one ordered list of setter calls.
    func apply(_ profile: EqProfile) {
        guard let value = DeviceEqValue(profile.deviceEq) else { return }
        remember(current)
        lastAppliedId = profile.id
        guard let scene = profile.scene else { return send(value) }
        let capabilities = DeviceCatalog.capabilities(model)
        controller.apply(HeadphoneScenes.intents(scene, current: controller.reportedState, capabilities: capabilities,
                                                 eq: HeadphoneScenes.eqIntent(profile.deviceEq, capabilities: capabilities)))
    }

    /// Live band edit. One undo entry per burst of edits (a drag), committed after a pause.
    func setBand(_ index: Int, to step: Int) {
        if editOrigin == nil { editOrigin = current }
        var updated = bands
        updated[index] = step
        lastAppliedId = nil
        controller.setEqualizerBands(updated)
        editCommitTask?.cancel()
        editCommitTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard let self, !Task.isCancelled else { return }
            self.remember(self.editOrigin)
            self.editOrigin = nil
        }
    }

    func resetFlat() {
        remember(current)
        lastAppliedId = "builtin.flat"
        controller.setEqualizerBands(Array(repeating: 0, count: spec.bandCount))
    }

    func undo() {
        guard let previous = history.popLast() else { return }
        compareWith = current
        lastAppliedId = nil
        send(previous)
        canUndo = !history.isEmpty
    }

    /// A/B: swaps the current curve with the previous one.
    func toggleCompare() {
        guard let other = compareWith else { return }
        compareWith = current
        lastAppliedId = nil
        send(other)
    }

    // MARK: - Library

    private func attempt(_ action: () throws -> Void) {
        do {
            try action()
        } catch {
            message = error.localizedDescription
        }
        userProfiles = library.userProfiles
    }

    func saveCurrent(as name: String) {
        attempt {
            switch current {
            case .bands(let steps):
                lastAppliedId = try library.saveCurve(name: name, modelId: model.rawValue, steps: steps).id
            case .preset(let code):
                if let base = profiles.first(where: { $0.deviceEq.presetCode == Int(code) }) {
                    lastAppliedId = try library.duplicate(base.id, name: name).id
                } else if let bands = reportedBands {
                    // A Sound Connect user setting: keep its curve as a custom profile.
                    lastAppliedId = try library.saveCurve(
                        name: name, modelId: model.rawValue, steps: bands,
                        notes: String(format: NSLocalizedString("Imported from a Sound Connect user setting (preset 0x%02x).", comment: ""), code)
                    ).id
                }
            case nil:
                break
            }
        }
    }

    // MARK: - Scenes

    /// "Guardar configuración completa" needs the state read from the headphones in this connection.
    var canSaveScene: Bool { controller.connectionState == .connected && controller.equalizer != nil }

    var suggestedSceneName: String {
        let date = DateFormatter.localizedString(from: Date(), dateStyle: .short, timeStyle: .none)
        return String(format: NSLocalizedString("My configuration %@", comment: ""), date)
    }

    /// Saves the EQ plus every other setting the headphones report as one profile (a scene).
    func saveScene(as name: String) {
        guard canSaveScene else { return }
        attempt {
            lastAppliedId = try library.saveScene(name: name, modelId: model.rawValue, state: controller.reportedState).id
        }
    }

    /// What applying `profile`'s scene would change; empty without a scene.
    func sceneChanges(for profile: EqProfile) -> [SceneChange] {
        guard let scene = profile.scene else { return [] }
        return HeadphoneScenes.changes(scene, current: controller.reportedState, capabilities: DeviceCatalog.capabilities(model))
    }

    /// Whether applying `profile` leaves a listening mode that turns the headphones' EQ off.
    func sceneLeavesEqInactive(_ profile: EqProfile) -> Bool {
        profile.scene.map { HeadphoneScenes.leavesEqInactive($0, current: controller.reportedState) } ?? false
    }

    /// Short name of a profile's EQ for the confirmation: the Sony preset, or "custom curve".
    func eqSummary(_ profile: EqProfile) -> String {
        if let code = profile.deviceEq.presetCode {
            return spec.presets.first { Int($0.code) == code }.map { NSLocalizedString($0.name, comment: "") }
                ?? String(format: "0x%02x", code)
        }
        return NSLocalizedString("custom curve", comment: "")
    }

    func duplicate(_ profile: EqProfile, as name: String) { attempt { try library.duplicate(profile.id, name: name) } }
    func rename(_ profile: EqProfile, to name: String) { attempt { try library.rename(profile.id, to: name) } }
    func delete(_ profile: EqProfile) { attempt { try library.delete(profile.id) } }

    func overwriteWithCurrent(_ profile: EqProfile) {
        guard let steps = current?.bands else { return }
        attempt { try library.updateSteps(profile.id, steps: steps) }
    }

    func exportData() -> Data? {
        do { return try library.exportData() } catch {
            message = error.localizedDescription
            return nil
        }
    }

    func importProfiles(_ data: Data) {
        attempt {
            let imported = try library.importData(data)
            message = String(format: NSLocalizedString("Imported %lld profiles", comment: ""), imported.count)
        }
    }

    // MARK: - AutoEQ

    func previewAutoEq(text: String, name: String) {
        let model = self.model
        Task {
            do {
                let result = try await Task.detached { try AutoEqImporter.fit(text, model: model) }.value
                autoEqPreview = AutoEqPreview(name: name.isEmpty ? "AutoEQ" : name, fit: result.fit, sourceCurve: result.sourceCurve)
            } catch {
                message = error.localizedDescription
            }
        }
    }

    func saveAutoEq(_ preview: AutoEqPreview, apply shouldApply: Bool) {
        attempt {
            let saved = try library.saveCurve(
                name: preview.name,
                modelId: model.rawValue,
                steps: preview.fit.steps,
                category: .measured,
                sourceCurve: preview.sourceCurve,
                source: ProfileSource(description: "AutoEq import, fitted to the headphones' \(spec.bandCount) bands (prediction)"),
                notes: String(format: "Error RMS ponderado %.2f dB", preview.fit.weightedRmsErrorDb)
            )
            if shouldApply { apply(saved) }
        }
        autoEqPreview = nil
    }
}
