import Foundation
import SonyHeadphonesKit

/// User profiles as one JSON file in the shared export format. Writes are atomic.
public struct ProfileStore: Sendable {
    public let url: URL

    public init(url: URL) {
        self.url = url
    }

    /// `~/Library/Application Support/XM Audio Studio/profiles.json`
    public static var standard: ProfileStore {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return ProfileStore(url: base.appendingPathComponent("XM Audio Studio/profiles.json"))
    }

    public func load() -> [EqProfile] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        do {
            return try ProfileCodec.decode(data)
        } catch {
            // Keep the unreadable file for diagnosis instead of overwriting the user's data.
            let backup = url.deletingLastPathComponent()
                .appendingPathComponent("\(url.lastPathComponent).corrupt-\(Int(Date().timeIntervalSince1970 * 1000))")
            try? FileManager.default.copyItem(at: url, to: backup)
            return []
        }
    }

    public func save(_ profiles: [EqProfile]) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try ProfileCodec.encode(profiles).write(to: url, options: .atomic)
    }
}

/// Built-in plus user profiles. Unlimited profiles live in the app; the headphones only ever
/// hold the single curve currently sent to them. Mirrors Kotlin `ProfileLibrary`.
public final class ProfileLibrary {
    private let store: ProfileStore
    private let now: () -> String
    private let newId: () -> String
    public private(set) var userProfiles: [EqProfile]

    public init(
        store: ProfileStore,
        now: @escaping () -> String = { ISO8601DateFormatter().string(from: Date()) },
        newId: @escaping () -> String = { UUID().uuidString.lowercased() }
    ) {
        self.store = store
        self.now = now
        self.newId = newId
        userProfiles = store.load()
    }

    public func forModel(_ modelId: String) -> [EqProfile] {
        BuiltinProfiles.forModel(modelId) + userProfiles.filter { $0.model == modelId }
    }

    public func find(_ id: String) -> EqProfile? {
        BuiltinProfiles.all.first { $0.id == id } ?? userProfiles.first { $0.id == id }
    }

    @discardableResult
    public func saveCurve(name: String, modelId: String, steps: [Int], category: ProfileCategory = .user,
                          sourceCurve: SourceCurve? = nil, source: ProfileSource? = nil, notes: String? = nil) throws -> EqProfile {
        let stamp = now()
        return try add(EqProfile(id: newId(), name: name.trimmingCharacters(in: .whitespaces), category: category, model: modelId,
                                 deviceEq: DeviceEqSetting(steps: steps), sourceCurve: sourceCurve, source: source,
                                 notes: notes, createdAt: stamp, updatedAt: stamp))
    }

    @discardableResult
    public func duplicate(_ id: String, name: String) throws -> EqProfile {
        guard var copy = find(id) else { throw ProfileFormatError(message: "No profile \(id)") }
        let stamp = now()
        copy.id = newId()
        copy.name = name.trimmingCharacters(in: .whitespaces)
        if copy.category != .measured && copy.category != .devicePreset { copy.category = .user }
        copy.createdAt = stamp
        copy.updatedAt = stamp
        return try add(copy)
    }

    @discardableResult
    public func rename(_ id: String, to name: String) throws -> EqProfile {
        try update(id) { $0.name = name.trimmingCharacters(in: .whitespaces) }
    }

    @discardableResult
    public func updateSteps(_ id: String, steps: [Int]) throws -> EqProfile {
        try update(id) {
            $0.deviceEq = DeviceEqSetting(steps: steps)
            if $0.category == .measured { $0.category = .user }
        }
    }

    public func delete(_ id: String) throws {
        guard !id.hasPrefix(EqProfile.builtinPrefix) else { throw ProfileFormatError(message: "Built-in profiles cannot be deleted") }
        userProfiles.removeAll { $0.id == id }
        try store.save(userProfiles)
    }

    /// Imports an export file. Every imported profile gets a fresh id so nothing is overwritten.
    @discardableResult
    public func importData(_ data: Data) throws -> [EqProfile] {
        let stamp = now()
        let imported = try ProfileCodec.decode(data).map { p -> EqProfile in
            var p = p
            p.id = newId()
            if p.category == .devicePreset && p.deviceEq.steps != nil { p.category = .user }
            p.updatedAt = stamp
            return p
        }
        userProfiles += imported
        try store.save(userProfiles)
        return imported
    }

    public func exportData(ids: Set<String>? = nil) throws -> Data {
        let selected = ids.map { ids in (BuiltinProfiles.all + userProfiles).filter { ids.contains($0.id) } } ?? userProfiles
        return try ProfileCodec.encode(selected, exportedAt: now())
    }

    private func add(_ profile: EqProfile) throws -> EqProfile {
        if let reason = ProfileCodec.validate(profile) { throw ProfileFormatError(message: reason) }
        userProfiles.append(profile)
        try store.save(userProfiles)
        return profile
    }

    private func update(_ id: String, _ change: (inout EqProfile) -> Void) throws -> EqProfile {
        guard !id.hasPrefix(EqProfile.builtinPrefix) else {
            throw ProfileFormatError(message: "Built-in profiles cannot be edited; duplicate them first")
        }
        guard let index = userProfiles.firstIndex(where: { $0.id == id }) else { throw ProfileFormatError(message: "No profile \(id)") }
        var updated = userProfiles[index]
        change(&updated)
        updated.updatedAt = now()
        if let reason = ProfileCodec.validate(updated) { throw ProfileFormatError(message: reason) }
        userProfiles[index] = updated
        try store.save(userProfiles)
        return updated
    }
}

/// "AutoEQ interno": fits an AutoEq ParametricEQ.txt / GraphicEQ.txt onto the headphones'
/// own fixed bands. Mirrors Kotlin `AutoEqImporter`.
public enum AutoEqImporter {
    /// Octave-spaced graphic EQ bands are modelled with Q = sqrt(2).
    public static let bandQ = 1.41

    public static func deviceModel(_ spec: EqualizerSpec) -> DeviceEqModel {
        DeviceEqModel(bandFrequencies: spec.bandFrequenciesHz, q: bandQ, stepDb: spec.stepDb, minStep: spec.minStep, maxStep: spec.maxStep)
    }

    public struct Result: Sendable {
        public let fit: FitResult
        public let sourceCurve: SourceCurve?
    }

    public static func fit(_ text: String, model: HeadphoneModel) throws -> Result {
        guard let spec = DeviceCatalog.capabilities(model).equalizer else {
            throw ProfileFormatError(message: "\(model.displayName) has no equalizer")
        }
        let grid = GraphicEqFitter.defaultGrid()
        do {
            if text.contains("GraphicEQ:") {
                let target = AutoEqParser.interpolate(try AutoEqParser.parseGraphic(text), at: grid)
                return Result(fit: GraphicEqFitter.fit(model: deviceModel(spec), targetDb: target, frequencies: grid), sourceCurve: nil)
            }
            let parametric = try AutoEqParser.parseParametric(text)
            return Result(fit: GraphicEqFitter.fit(model: deviceModel(spec), targetDb: parametric.responseDb(at: grid), frequencies: grid),
                          sourceCurve: SourceCurve(parametric))
        } catch let error as AutoEqParser.ParseError {
            throw ProfileFormatError(message: error.description)
        }
    }
}
