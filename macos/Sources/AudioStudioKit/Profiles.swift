import Foundation
import SonyHeadphonesKit

/// Profile model of `shared/preset-schema/profiles.schema.json`; the Android app reads and
/// writes the same files (`android/core/.../presets/Profiles.kt`).
public enum ProfileCategory: String, Codable, Sendable, CaseIterable {
    /// A Sony built-in preset selected by code.
    case devicePreset = "device-preset"
    /// Derived from an acoustic measurement (e.g. an imported AutoEq profile).
    case measured
    /// Designed by taste; never presented as a correction.
    case subjective
    case user
}

/// Exactly one of `presetCode` (select a Sony preset) or `steps` (custom curve).
public struct DeviceEqSetting: Codable, Equatable, Sendable {
    public var presetCode: Int?
    public var steps: [Int]?
    public var clearBass: Int?

    public init(presetCode: Int? = nil, steps: [Int]? = nil, clearBass: Int? = nil) {
        self.presetCode = presetCode
        self.steps = steps
        self.clearBass = clearBass
    }
}

public struct SourceCurve: Codable, Equatable, Sendable {
    public var preampDb: Double
    public var filters: [ParametricFilter]

    public init(_ profile: ParametricProfile) {
        preampDb = profile.preampDb
        filters = profile.filters
    }

    public var parametric: ParametricProfile { ParametricProfile(preampDb: preampDb, filters: filters) }
}

public struct ProfileSource: Codable, Equatable, Sendable {
    public var description: String?
    public var url: String?
    public var attribution: String?

    public init(description: String? = nil, url: String? = nil, attribution: String? = nil) {
        self.description = description
        self.url = url
        self.attribution = attribution
    }
}

public struct EqProfile: Codable, Equatable, Sendable, Identifiable {
    public static let builtinPrefix = "builtin."

    public var id: String
    public var name: String
    public var category: ProfileCategory
    /// `HeadphoneModel.rawValue`; a profile is only offered for, and applied to, its own model.
    public var model: String
    public var deviceEq: DeviceEqSetting
    public var sourceCurve: SourceCurve?
    public var source: ProfileSource?
    public var notes: String?
    public var createdAt: String?
    public var updatedAt: String?

    public init(id: String, name: String, category: ProfileCategory, model: String, deviceEq: DeviceEqSetting,
                sourceCurve: SourceCurve? = nil, source: ProfileSource? = nil, notes: String? = nil,
                createdAt: String? = nil, updatedAt: String? = nil) {
        self.id = id
        self.name = name
        self.category = category
        self.model = model
        self.deviceEq = deviceEq
        self.sourceCurve = sourceCurve
        self.source = source
        self.notes = notes
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    public var isBuiltin: Bool { id.hasPrefix(Self.builtinPrefix) }
}

public struct ProfileFormatError: Error, Equatable, LocalizedError {
    public let message: String
    public var errorDescription: String? { message }
}

public enum ProfileCodec {
    public static let format = "xm-audio-studio.profiles"
    public static let version = 1
    public static let maxNameLength = 80

    struct Export: Codable {
        var format: String
        var version: Int
        var exportedAt: String?
        var profiles: [EqProfile]
    }

    public static func encode(_ profiles: [EqProfile], exportedAt: String? = nil) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes]
        return try encoder.encode(Export(format: format, version: version, exportedAt: exportedAt, profiles: profiles))
    }

    /// Parses and validates an export file; throws `ProfileFormatError` with a readable reason.
    public static func decode(_ data: Data) throws -> [EqProfile] {
        let doc: Export
        do {
            doc = try JSONDecoder().decode(Export.self, from: data)
        } catch {
            throw ProfileFormatError(message: "Not a valid profile file: \(error.localizedDescription)")
        }
        guard doc.format == format else { throw ProfileFormatError(message: "Unknown format '\(doc.format)'") }
        guard doc.version <= version else {
            throw ProfileFormatError(message: "Profile file version \(doc.version) is newer than this app supports (\(version))")
        }
        for p in doc.profiles {
            if let reason = validate(p) {
                throw ProfileFormatError(message: "\(p.name.trimmingCharacters(in: .whitespaces).isEmpty ? p.id : p.name): \(reason)")
            }
        }
        return doc.profiles
    }

    /// Nil when valid, otherwise the reason. Same rules as the Kotlin `ProfileCodec.validate`.
    public static func validate(_ p: EqProfile) -> String? {
        if p.id.trimmingCharacters(in: .whitespaces).isEmpty { return "missing id" }
        let name = p.name.trimmingCharacters(in: .whitespaces)
        if name.isEmpty || p.name.count > maxNameLength { return "name must be 1-\(maxNameLength) characters" }
        guard let model = HeadphoneModel(rawValue: p.model) else { return "unknown model '\(p.model)'" }
        guard let spec = DeviceCatalog.capabilities(model).equalizer else { return "\(model.displayName) has no equalizer" }
        let eq = p.deviceEq
        if (eq.presetCode == nil) == (eq.steps == nil) { return "needs exactly one of presetCode or steps" }
        if let code = eq.presetCode, !spec.presets.contains(where: { Int($0.code) == code }) {
            return String(format: "preset 0x%02x is not available on %@", code, model.displayName)
        }
        if let steps = eq.steps {
            if steps.count != spec.bandCount { return "expected \(spec.bandCount) bands, found \(steps.count)" }
            if steps.contains(where: { $0 < spec.minStep || $0 > spec.maxStep }) {
                return "band values must be within \(spec.minStep)..\(spec.maxStep)"
            }
        }
        if let cb = eq.clearBass {
            guard let range = spec.clearBassRange else { return "\(model.displayName) has no Clear Bass" }
            if !range.contains(cb) { return "Clear Bass must be within \(range.lowerBound)..\(range.upperBound)" }
        }
        return nil
    }
}

/// Profiles shipped with the app. Must equal `shared/preset-schema/builtin-profiles.json`.
public enum BuiltinProfiles {
    private static let subjective = "Perfil subjetivo diseñado por gusto, no una corrección medida."
    private static let xm6 = HeadphoneModel.wh1000xm6.rawValue

    private static func preset(_ id: String, _ name: String, _ code: Int, _ notes: String? = nil) -> EqProfile {
        EqProfile(id: "builtin.\(id)", name: name, category: .devicePreset, model: xm6, deviceEq: DeviceEqSetting(presetCode: code), notes: notes)
    }

    private static func curve(_ id: String, _ name: String, _ steps: [Int], _ notes: String = subjective) -> EqProfile {
        EqProfile(id: "builtin.\(id)", name: name, category: .subjective, model: xm6, deviceEq: DeviceEqSetting(steps: steps), notes: notes)
    }

    public static let all: [EqProfile] = [
        preset("sony-original", "Original Sony", 0x00, "Desactiva el EQ de los audífonos: afinación de fábrica de Sony."),
        curve("flat", "Plano", Array(repeating: 0, count: 10), "Curva personalizada a cero. Equivale al sonido de fábrica, no a una respuesta neutra medida."),
        curve("controlled-bass", "Graves controlados", [-3, -2, -1, 0, 0, 0, 0, 0, 0, 0]),
        curve("clear-vocals", "Voces claras", [-2, -1, -1, 0, 1, 2, 3, 2, 0, 0]),
        curve("electronic", "Música electrónica", [4, 3, 1, 0, -1, -1, 0, 1, 2, 2]),
        curve("rock", "Rock", [3, 2, 0, -1, -1, 0, 1, 2, 2, 1]),
        curve("classical", "Música clásica", [1, 1, 0, 0, -1, -1, 0, 1, 2, 1]),
        curve("movies", "Películas", [3, 2, 1, 0, 0, 1, 2, 1, 0, 0]),
        curve("gaming", "Videojuegos", [1, 1, 0, -1, 0, 1, 3, 3, 1, 0]),
        curve("podcast", "Podcast", [-4, -3, -2, 0, 1, 2, 2, 1, -1, -2]),
        preset("sony-heavy", "Sony Heavy", 0x30),
        preset("sony-clear", "Sony Clear", 0x31),
        preset("sony-hard", "Sony Hard", 0x32),
        preset("sony-soft", "Sony Soft", 0x33),
    ]

    public static func forModel(_ modelId: String) -> [EqProfile] { all.filter { $0.model == modelId } }
}
