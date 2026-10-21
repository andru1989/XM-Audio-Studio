import Foundation

public enum FilterType: String, Codable, Sendable, CaseIterable {
    case peaking
    case lowShelf
    case highShelf
}

public struct ParametricFilter: Equatable, Codable, Sendable {
    public var type: FilterType
    public var frequency: Double
    public var gainDb: Double
    public var q: Double

    public init(type: FilterType, frequency: Double, gainDb: Double, q: Double) {
        self.type = type
        self.frequency = frequency
        self.gainDb = gainDb
        self.q = q
    }
}

/// Normalised biquad coefficients (a0 == 1) from the RBJ Audio EQ Cookbook.
/// Mirrors `android/core/.../eq/Biquad.kt`; `shared/autoeq-test-vectors` pins both.
public struct BiquadCoefficients: Equatable, Sendable {
    public var b0, b1, b2, a1, a2: Double

    public init(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        self.b0 = b0; self.b1 = b1; self.b2 = b2; self.a1 = a1; self.a2 = a2
    }

    public init(_ filter: ParametricFilter, sampleRate: Double) {
        let a = pow(10.0, filter.gainDb / 40.0)
        let w0 = 2.0 * Double.pi * filter.frequency / sampleRate
        let cw = cos(w0)
        let alpha = sin(w0) / (2.0 * filter.q)
        let b0, b1, b2, a0, a1, a2: Double
        switch filter.type {
        case .peaking:
            b0 = 1.0 + alpha * a
            b1 = -2.0 * cw
            b2 = 1.0 - alpha * a
            a0 = 1.0 + alpha / a
            a1 = -2.0 * cw
            a2 = 1.0 - alpha / a
        case .lowShelf:
            let k = 2.0 * sqrt(a) * alpha
            b0 = a * ((a + 1) - (a - 1) * cw + k)
            b1 = 2.0 * a * ((a - 1) - (a + 1) * cw)
            b2 = a * ((a + 1) - (a - 1) * cw - k)
            a0 = (a + 1) + (a - 1) * cw + k
            a1 = -2.0 * ((a - 1) + (a + 1) * cw)
            a2 = (a + 1) + (a - 1) * cw - k
        case .highShelf:
            let k = 2.0 * sqrt(a) * alpha
            b0 = a * ((a + 1) + (a - 1) * cw + k)
            b1 = -2.0 * a * ((a - 1) + (a + 1) * cw)
            b2 = a * ((a + 1) + (a - 1) * cw - k)
            a0 = (a + 1) - (a - 1) * cw + k
            a1 = 2.0 * ((a - 1) - (a + 1) * cw)
            a2 = (a + 1) - (a - 1) * cw - k
        }
        self.init(b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0)
    }

    /// |H(e^jw)| in dB at `frequency` Hz.
    public func magnitudeDb(at frequency: Double, sampleRate: Double) -> Double {
        let w = 2.0 * Double.pi * frequency / sampleRate
        let cw = cos(w), c2w = cos(2.0 * w), sw = sin(w), s2w = sin(2.0 * w)
        let numRe = b0 + b1 * cw + b2 * c2w
        let numIm = -(b1 * sw + b2 * s2w)
        let denRe = 1.0 + a1 * cw + a2 * c2w
        let denIm = -(a1 * sw + a2 * s2w)
        let num = numRe * numRe + numIm * numIm
        let den = denRe * denRe + denIm * denIm
        return 10.0 * log10(max(num, 1e-30) / max(den, 1e-30))
    }
}

/// A parametric EQ (e.g. an AutoEq `ParametricEQ.txt`): preamp plus a chain of biquads.
public struct ParametricProfile: Equatable, Codable, Sendable {
    public static let defaultSampleRate = 48_000.0

    public var preampDb: Double
    public var filters: [ParametricFilter]

    public init(preampDb: Double, filters: [ParametricFilter]) {
        self.preampDb = preampDb
        self.filters = filters
    }

    /// Response of the filter chain in dB; `includePreamp` adds the preamp.
    public func responseDb(at frequencies: [Double], sampleRate: Double = defaultSampleRate, includePreamp: Bool = false) -> [Double] {
        let coefficients = filters.map { BiquadCoefficients($0, sampleRate: sampleRate) }
        return frequencies.map { f in
            var sum = includePreamp ? preampDb : 0.0
            for c in coefficients { sum += c.magnitudeDb(at: f, sampleRate: sampleRate) }
            return sum
        }
    }
}

public enum FrequencyGrid {
    /// `count` log-spaced points from `min` to `max` inclusive.
    public static func logSpaced(min: Double = 20, max: Double = 20_000, count: Int = 96) -> [Double] {
        precondition(count >= 2)
        let ratio = log10(max / min)
        return (0..<count).map { min * pow(10.0, ratio * Double($0) / Double(count - 1)) }
    }
}
