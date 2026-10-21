import Foundation
import AudioStudioKit

extension BiquadCoefficients {
    /// The pass-through section used to pad unused slots of the vDSP cascade.
    public static let unity = BiquadCoefficients(b0: 1, b1: 0, b2: 0, a1: 0, a2: 0)

    /// RBJ coefficients for the realtime chain. Same maths as AudioStudioKit (and the
    /// shared test vectors); only the centre frequency is clamped to 10 Hz…0.499·fs so a
    /// band above Nyquist at a low sample rate (e.g. Bluetooth HFP at 16 kHz) stays a
    /// valid filter, as ParaEQ does.
    public static func realtime(_ filter: ParametricFilter, sampleRate: Double) -> BiquadCoefficients {
        // A 0 dB peaking or shelving filter is exactly the identity (numerator equals
        // denominator); running it would only add roundoff.
        if filter.gainDb == 0 { return .unity }
        var f = filter
        f.frequency = min(max(filter.frequency, 10), sampleRate * 0.499)
        f.q = max(filter.q, 0.01)
        let c = BiquadCoefficients(f, sampleRate: sampleRate)
        return c.isFinite && c.isStable ? c : .unity
    }

    public var isFinite: Bool { b0.isFinite && b1.isFinite && b2.isFinite && a1.isFinite && a2.isFinite }

    /// Both poles strictly inside the unit circle (the stability triangle for a0 = 1).
    public var isStable: Bool { abs(a2) < 1 && abs(a1) < 1 + a2 }
}

/// The coefficient matrix the realtime chain runs for one sample rate and channel
/// count: `sectionCount` cascaded biquads per channel, unused sections set to unity.
/// Also the single source for the response graph and automatic headroom, so the
/// curve on screen is the curve being processed.
public struct DSPCoefficientPlan: Sendable {
    public let sampleRate: Double
    public let channelCount: Int
    public let sectionCount: Int
    /// `sections[channel][section]`.
    public let sections: [[BiquadCoefficients]]
    /// Largest gain of any channel's cascade on the analysis grid, in dB.
    public let peakGainDb: Double
    /// Preamp actually applied: −max(0, peak) with automatic headroom, else the manual value.
    public let preampDb: Double

    public init(settings raw: DSPSettings, sampleRate: Double, channelCount: Int, sectionCount: Int = DSPSettings.maxBands) {
        precondition(sampleRate > 0 && channelCount > 0 && sectionCount > 0)
        let settings = raw.sanitized()
        self.sampleRate = sampleRate
        self.channelCount = channelCount
        self.sectionCount = sectionCount

        let active = settings.bands.filter(\.isEnabled)
        var sections: [[BiquadCoefficients]] = []
        for channel in 0..<channelCount {
            var chain = active.filter { $0.channels.includes(channel: channel) }
                .prefix(sectionCount)
                .map { BiquadCoefficients.realtime($0.filter, sampleRate: sampleRate) }
            chain.append(contentsOf: repeatElement(.unity, count: sectionCount - chain.count))
            sections.append(chain)
        }
        self.sections = sections

        // Dense log grid plus every band centre, so a narrow peak cannot slip between points.
        let top = min(20_000, sampleRate * 0.499)
        var grid = FrequencyGrid.logSpaced(min: 20, max: max(top, 21), count: 256)
        grid.append(contentsOf: active.map { min(max($0.filter.frequency, 20), top) })
        var peak = -Double.infinity
        for chain in sections {
            for f in grid {
                let db = chain.reduce(0.0) { $0 + $1.magnitudeDb(at: f, sampleRate: sampleRate) }
                peak = max(peak, db)
            }
        }
        peakGainDb = peak.isFinite ? peak : 0
        preampDb = settings.autoHeadroom ? -max(0, peakGainDb) : settings.preampDb
    }

    /// Cascade response of one channel in dB (without the preamp).
    public func responseDb(at frequencies: [Double], channel: Int = 0) -> [Double] {
        let chain = sections[min(channel, channelCount - 1)]
        return frequencies.map { f in chain.reduce(0.0) { $0 + $1.magnitudeDb(at: f, sampleRate: sampleRate) } }
    }

    /// Number of doubles in the vDSP_biquadm coefficient matrix.
    var coefficientCount: Int { sectionCount * channelCount * 5 }

    /// vDSP_biquadm layout: section-major, channel-minor, `[b0 b1 b2 a1 a2]` each.
    func write(into buffer: UnsafeMutablePointer<Double>) {
        var i = 0
        for s in 0..<sectionCount {
            for c in 0..<channelCount {
                let k = sections[c][s]
                buffer[i] = k.b0; buffer[i + 1] = k.b1; buffer[i + 2] = k.b2
                buffer[i + 3] = k.a1; buffer[i + 4] = k.a2
                i += 5
            }
        }
    }
}
