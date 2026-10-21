import Foundation
import AudioStudioKit

/// Which channels a band applies to. Even channel indices count as left, odd as right,
/// so a stereo stream maps 0 = L, 1 = R.
public enum DSPChannelScope: String, Codable, Sendable, CaseIterable {
    case both
    case left
    case right

    func includes(channel: Int) -> Bool {
        switch self {
        case .both: return true
        case .left: return channel % 2 == 0
        case .right: return channel % 2 == 1
        }
    }
}

/// One parametric band of the local DSP equalizer.
public struct DSPBand: Codable, Equatable, Sendable, Identifiable {
    public var id: UUID
    public var filter: ParametricFilter
    public var isEnabled: Bool
    public var channels: DSPChannelScope

    public init(id: UUID = UUID(), filter: ParametricFilter, isEnabled: Bool = true, channels: DSPChannelScope = .both) {
        self.id = id
        self.filter = filter
        self.isEnabled = isEnabled
        self.channels = channels
    }

    public init(_ type: FilterType, frequency: Double, gainDb: Double, q: Double, channels: DSPChannelScope = .both) {
        self.init(filter: ParametricFilter(type: type, frequency: frequency, gainDb: gainDb, q: q), channels: channels)
    }
}

/// Where the current band set came from, so the UI can stop the same headphone
/// correction being applied twice (once by the headphones' EQ, once here).
public struct DSPCorrectionSource: Codable, Equatable, Sendable {
    public var profileId: String
    public var profileName: String

    public init(profileId: String, profileName: String) {
        self.profileId = profileId
        self.profileName = profileName
    }
}

/// Everything the user can set on the local DSP equalizer. Plain value; the processor
/// turns it into coefficients for whatever sample rate it is running at.
public struct DSPSettings: Codable, Equatable, Sendable {
    public static let maxBands = 20
    public static let gainRangeDb: ClosedRange<Double> = -24...24
    public static let qRange: ClosedRange<Double> = 0.1...20
    public static let frequencyRangeHz: ClosedRange<Double> = 10...24_000
    public static let preampRangeDb: ClosedRange<Double> = -30...12

    public var bands: [DSPBand]
    /// Manual preamp, used when `autoHeadroom` is off.
    public var preampDb: Double
    /// Preamp = −(largest boost of the curve), so the EQ itself never pushes a
    /// full-scale signal over 0 dBFS. On by default.
    public var autoHeadroom: Bool
    /// A/B: the output is the untouched input (bit-exact once the short crossfade ends).
    public var isBypassed: Bool
    public var correctionSource: DSPCorrectionSource?

    public init(bands: [DSPBand] = [], preampDb: Double = 0, autoHeadroom: Bool = true,
                isBypassed: Bool = false, correctionSource: DSPCorrectionSource? = nil) {
        self.bands = bands
        self.preampDb = preampDb
        self.autoHeadroom = autoHeadroom
        self.isBypassed = isBypassed
        self.correctionSource = correctionSource
    }

    public static let flat = DSPSettings()

    /// Bands taken from an AutoEq parametric curve (`SourceCurve` of a measured profile).
    public static func from(curve: ParametricProfile, source: DSPCorrectionSource?, autoHeadroom: Bool = true) -> DSPSettings {
        DSPSettings(
            bands: curve.filters.prefix(maxBands).map { DSPBand(filter: $0) },
            preampDb: curve.preampDb,
            autoHeadroom: autoHeadroom,
            correctionSource: source
        )
    }

    /// Values clamped to what the processor accepts; non-finite numbers disable the band.
    public func sanitized() -> DSPSettings {
        var copy = self
        copy.bands = bands.prefix(Self.maxBands).map { band in
            var b = band
            let f = band.filter
            if !f.frequency.isFinite || !f.gainDb.isFinite || !f.q.isFinite {
                b.isEnabled = false
                b.filter = ParametricFilter(type: f.type, frequency: 1000, gainDb: 0, q: 1)
                return b
            }
            b.filter.frequency = f.frequency.clamped(to: Self.frequencyRangeHz)
            b.filter.gainDb = f.gainDb.clamped(to: Self.gainRangeDb)
            b.filter.q = f.q.clamped(to: Self.qRange)
            return b
        }
        copy.preampDb = preampDb.isFinite ? preampDb.clamped(to: Self.preampRangeDb) : 0
        return copy
    }
}

extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self { min(max(self, range.lowerBound), range.upperBound) }
}
