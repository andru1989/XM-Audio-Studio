import XCTest
import AudioStudioKit
@testable import AudioDSPKit

final class CoefficientPlanTests: XCTestCase {
    /// docs/research/dsp-autoeq-research.md §3.3 (also shared/autoeq-test-vectors).
    func testRealtimeCoefficientsMatchTheSharedRBJVectors() {
        let pk = BiquadCoefficients.realtime(ParametricFilter(type: .peaking, frequency: 1_000, gainDb: 6, q: 1.41), sampleRate: 48_000)
        XCTAssertEqual(pk.b0, 1.031577910617, accuracy: 1e-9)
        XCTAssertEqual(pk.b1, -1.919976143598, accuracy: 1e-9)
        XCTAssertEqual(pk.b2, 0.904965631439, accuracy: 1e-9)
        XCTAssertEqual(pk.a1, -1.919976143598, accuracy: 1e-9)
        XCTAssertEqual(pk.a2, 0.936543542056, accuracy: 1e-9)

        let hs = BiquadCoefficients.realtime(ParametricFilter(type: .highShelf, frequency: 10_000, gainDb: -4.1, q: 0.7), sampleRate: 48_000)
        XCTAssertEqual(hs.b0, 0.761777919891, accuracy: 1e-9)
        XCTAssertEqual(hs.a2, 0.201233650032, accuracy: 1e-9)
    }

    func testCoefficientsAreFiniteAndStableAcrossTheParameterSpace() {
        let rates = [8_000.0, 16_000, 24_000, 44_100, 48_000, 96_000, 192_000]
        let frequencies = FrequencyGrid.logSpaced(min: 10, max: 24_000, count: 40)
        let gains = [-24.0, -12, -3, 0, 0.5, 6, 12, 24]
        let qs = [0.1, 0.3, 0.7071, 1.41, 4, 10, 20]
        var checked = 0
        for fs in rates {
            for type in FilterType.allCases {
                for f in frequencies {
                    for g in gains {
                        for q in qs {
                            let c = BiquadCoefficients.realtime(ParametricFilter(type: type, frequency: f, gainDb: g, q: q), sampleRate: fs)
                            XCTAssertTrue(c.isFinite, "\(type) \(f) Hz \(g) dB Q\(q) @\(fs)")
                            XCTAssertTrue(c.isStable, "\(type) \(f) Hz \(g) dB Q\(q) @\(fs): a1 \(c.a1) a2 \(c.a2)")
                            checked += 1
                        }
                    }
                }
            }
        }
        XCTAssertEqual(checked, rates.count * 3 * 40 * gains.count * qs.count)
    }

    func testPlanPadsUnusedSectionsWithUnityAndSkipsDisabledBands() {
        var disabled = DSPBand(.peaking, frequency: 500, gainDb: 9, q: 1)
        disabled.isEnabled = false
        let settings = DSPSettings(bands: [
            DSPBand(.peaking, frequency: 1_000, gainDb: 3, q: 1),
            disabled,
            DSPBand(.lowShelf, frequency: 100, gainDb: 2, q: 0.7, channels: .right),
        ])
        let plan = DSPCoefficientPlan(settings: settings, sampleRate: 48_000, channelCount: 2, sectionCount: 6)
        XCTAssertEqual(plan.sections[0].filter { $0 != .unity }.count, 1)   // L: the peak
        XCTAssertEqual(plan.sections[1].filter { $0 != .unity }.count, 2)   // R: the peak and the right-only shelf
        XCTAssertEqual(plan.sections[0][1...].allSatisfy { $0 == .unity }, true)

        var buffer = [Double](repeating: .nan, count: plan.coefficientCount)
        buffer.withUnsafeMutableBufferPointer { plan.write(into: $0.baseAddress!) }
        XCTAssertFalse(buffer.contains { $0.isNaN })
        // vDSP order: section-major, channel-minor. Section 0, channel 1 starts at index 5.
        XCTAssertEqual(buffer[5], plan.sections[1][0].b0)
        XCTAssertEqual(buffer[10], plan.sections[0][1].b0)
    }

    func testSanitizingClampsValuesAndDisablesNonFiniteBands() {
        let settings = DSPSettings(bands: [
            DSPBand(.peaking, frequency: .nan, gainDb: 3, q: 1),
            DSPBand(.peaking, frequency: 99_000, gainDb: 80, q: 0),
        ], preampDb: -.infinity).sanitized()
        XCTAssertFalse(settings.bands[0].isEnabled)
        XCTAssertEqual(settings.bands[1].filter.frequency, DSPSettings.frequencyRangeHz.upperBound)
        XCTAssertEqual(settings.bands[1].filter.gainDb, DSPSettings.gainRangeDb.upperBound)
        XCTAssertEqual(settings.bands[1].filter.q, DSPSettings.qRange.lowerBound)
        XCTAssertEqual(settings.preampDb, 0)

        let many = DSPSettings(bands: (0..<40).map { DSPBand(.peaking, frequency: 100 + Double($0) * 100, gainDb: 1, q: 1) })
        XCTAssertEqual(many.sanitized().bands.count, DSPSettings.maxBands)
    }

    func testBandsAboveNyquistStayValidAtLowSampleRates() {
        // Bluetooth HFP runs at 16 kHz: a 10 kHz band must not produce a broken filter.
        let settings = DSPSettings(bands: [DSPBand(.highShelf, frequency: 10_000, gainDb: 6, q: 0.7),
                                           DSPBand(.peaking, frequency: 12_000, gainDb: 6, q: 2)])
        let plan = DSPCoefficientPlan(settings: settings, sampleRate: 16_000, channelCount: 1)
        XCTAssertTrue(plan.sections[0].allSatisfy { $0.isFinite && $0.isStable })
        XCTAssertTrue(plan.peakGainDb.isFinite)
    }

    func testCurveFromAutoEqSourceKeepsFiltersAndProvenance() {
        let curve = ParametricProfile(preampDb: -6.2, filters: [
            ParametricFilter(type: .lowShelf, frequency: 105, gainDb: 5, q: 0.7),
            ParametricFilter(type: .peaking, frequency: 2_000, gainDb: -3, q: 1.2),
        ])
        let source = DSPCorrectionSource(profileId: "user.abc", profileName: "XM6 AutoEQ")
        let settings = DSPSettings.from(curve: curve, source: source)
        XCTAssertEqual(settings.bands.map(\.filter), curve.filters)
        XCTAssertEqual(settings.preampDb, -6.2)
        XCTAssertEqual(settings.correctionSource, source)
        XCTAssertTrue(settings.autoHeadroom)

        let roundTrip = try? JSONDecoder().decode(DSPSettings.self, from: JSONEncoder().encode(settings))
        XCTAssertEqual(roundTrip, settings)
    }
}
