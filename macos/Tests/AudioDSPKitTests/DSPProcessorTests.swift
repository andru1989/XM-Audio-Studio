import XCTest
import AudioStudioKit
@testable import AudioDSPKit

final class DSPProcessorTests: XCTestCase {
    private let fs = 48_000.0

    private func processor(_ settings: DSPSettings, channels: Int = 2, sampleRate: Double = 48_000) throws -> DSPProcessor {
        try XCTUnwrap(DSPProcessor(settings: settings, sampleRate: sampleRate, channelCount: channels))
    }

    private var busyCurve: [DSPBand] {
        [
            DSPBand(.lowShelf, frequency: 105, gainDb: 6, q: 0.7),
            DSPBand(.peaking, frequency: 1_000, gainDb: 9, q: 1.41),
            DSPBand(.peaking, frequency: 3_200, gainDb: -7, q: 4),
            DSPBand(.highShelf, frequency: 10_000, gainDb: 5, q: 0.7),
        ]
    }

    // MARK: - Bypass

    func testBypassIsBitExact() throws {
        let p = try processor(DSPSettings(bands: busyCurve, preampDb: 6, autoHeadroom: false, isBypassed: true))
        // Includes samples over full scale: bypass must not even clamp them.
        let left = TestSignals.noise(frames: 20_000, amplitude: 1.8, seed: 1)
        let right = TestSignals.noise(frames: 20_000, amplitude: 0.3, seed: 2)
        var buffer = [left, right]
        p.process(buffer: &buffer)
        XCTAssertEqual(buffer[0], left)
        XCTAssertEqual(buffer[1], right)
    }

    func testBypassBecomesBitExactAfterTheCrossfade() throws {
        let p = try processor(DSPSettings(bands: busyCurve))
        var warm = [TestSignals.noise(frames: 4_800, seed: 3), TestSignals.noise(frames: 4_800, seed: 4)]
        p.process(buffer: &warm)
        var settings = p.settings
        settings.isBypassed = true
        p.apply(settings)
        var fade = [TestSignals.noise(frames: 960, seed: 5), TestSignals.noise(frames: 960, seed: 6)]
        p.process(buffer: &fade)   // 20 ms > the 10 ms crossfade
        let input = [TestSignals.noise(frames: 9_000, seed: 7), TestSignals.noise(frames: 9_000, seed: 8)]
        var buffer = input
        p.process(buffer: &buffer)
        XCTAssertEqual(buffer, input)

        // And back: processed again (not equal to the input any more).
        settings.isBypassed = false
        p.apply(settings)
        var again = input
        p.process(buffer: &again)
        XCTAssertNotEqual(again[0], input[0])
    }

    // MARK: - Unity

    func testZeroGainFiltersAreUnity() throws {
        let bands = [
            DSPBand(.peaking, frequency: 1_000, gainDb: 0, q: 1.41),
            DSPBand(.lowShelf, frequency: 100, gainDb: 0, q: 0.7),
            DSPBand(.highShelf, frequency: 8_000, gainDb: 0, q: 0.7),
            DSPBand(.peaking, frequency: 60, gainDb: 0, q: 8),
        ]
        for settings in [DSPSettings(bands: bands), DSPSettings.flat, DSPSettings(bands: bands, autoHeadroom: false)] {
            let p = try processor(settings)
            XCTAssertEqual(p.plan.preampDb, 0, accuracy: 1e-9)
            let input = [TestSignals.noise(frames: 30_000, seed: 11), TestSignals.noise(frames: 30_000, seed: 12)]
            var buffer = input
            p.process(buffer: &buffer)
            let latency = p.latencyFrames
            XCTAssertEqual(latency, 240) // 5 ms lookahead at 48 kHz
            var maxError: Float = 0
            for c in 0..<2 {
                for n in 0..<(input[c].count - latency) {
                    maxError = max(maxError, abs(buffer[c][n + latency] - input[c][n]))
                }
                XCTAssertTrue(buffer[c][0..<latency].allSatisfy { $0 == 0 }, "limiter lookahead delays the signal")
            }
            XCTAssertLessThan(maxError, 1e-5)
        }
    }

    // MARK: - Gain accuracy

    func testSineAtPeakingCentreGainsTheBandGain() throws {
        for (gain, rate) in [(6.0, 48_000.0), (-6.0, 48_000.0), (12.0, 44_100.0), (3.5, 96_000.0)] {
            let settings = DSPSettings(bands: [DSPBand(.peaking, frequency: 1_000, gainDb: gain, q: 1.41)], autoHeadroom: false)
            let p = try processor(settings, sampleRate: rate)
            let input = TestSignals.sine(1_000, sampleRate: rate, frames: Int(rate), amplitude: 0.2)
            var buffer = [input, input]
            p.process(buffer: &buffer)
            XCTAssertEqual(TestSignals.gainDb(input: input, output: buffer[0]), gain, accuracy: 0.1, "gain \(gain) dB at \(rate) Hz")
            XCTAssertEqual(TestSignals.gainDb(input: input, output: buffer[1]), gain, accuracy: 0.1)
        }
    }

    func testMeasuredGainFollowsTheCoefficientResponse() throws {
        let settings = DSPSettings(bands: busyCurve, autoHeadroom: false)
        let p = try processor(settings)
        for f in [40.0, 105, 400, 1_000, 3_200, 6_000, 12_000] {
            let input = TestSignals.sine(f, sampleRate: fs, frames: 48_000, amplitude: 0.1)
            var buffer = [input, input]
            p.process(buffer: &buffer)
            let expected = p.plan.responseDb(at: [f])[0]
            XCTAssertEqual(TestSignals.gainDb(input: input, output: buffer[0]), expected, accuracy: 0.1, "\(f) Hz")
        }
    }

    func testAutomaticHeadroomCancelsTheLargestBoost() throws {
        let p = try processor(DSPSettings(bands: [DSPBand(.peaking, frequency: 1_000, gainDb: 6, q: 1.41)]))
        XCTAssertEqual(p.plan.peakGainDb, 6, accuracy: 1e-6)
        XCTAssertEqual(p.plan.preampDb, -6, accuracy: 1e-6)
        let input = TestSignals.sine(1_000, sampleRate: fs, frames: 48_000)
        var buffer = [input, input]
        p.process(buffer: &buffer)
        XCTAssertEqual(TestSignals.gainDb(input: input, output: buffer[0]), 0, accuracy: 0.1)

        // Cuts only: no positive boost, so no preamp.
        let cuts = try processor(DSPSettings(bands: [DSPBand(.peaking, frequency: 1_000, gainDb: -6, q: 1.41)]))
        XCTAssertEqual(cuts.plan.preampDb, 0, accuracy: 1e-9)
        // Manual preamp when automatic headroom is off.
        let manual = try processor(DSPSettings(bands: busyCurve, preampDb: -3, autoHeadroom: false))
        XCTAssertEqual(manual.plan.preampDb, -3)
    }

    func testPerChannelBandsOnlyTouchTheirChannel() throws {
        let settings = DSPSettings(bands: [DSPBand(.peaking, frequency: 1_000, gainDb: 6, q: 1.41, channels: .left)], autoHeadroom: false)
        let p = try processor(settings)
        let input = TestSignals.sine(1_000, sampleRate: fs, frames: 48_000)
        var buffer = [input, input]
        p.process(buffer: &buffer)
        XCTAssertEqual(TestSignals.gainDb(input: input, output: buffer[0]), 6, accuracy: 0.1)
        XCTAssertEqual(TestSignals.gainDb(input: input, output: buffer[1]), 0, accuracy: 0.01)
    }

    // MARK: - Protection

    func testClippingProtectionKeepsOutputWithinFullScale() throws {
        let settings = DSPSettings(bands: [DSPBand(.lowShelf, frequency: 200, gainDb: 12, q: 0.7),
                                           DSPBand(.peaking, frequency: 2_000, gainDb: 12, q: 2)],
                                   preampDb: 12, autoHeadroom: false)
        let p = try processor(settings)
        var left = TestSignals.noise(frames: 48_000, amplitude: 4, seed: 21)
        var right = TestSignals.sine(100, sampleRate: fs, frames: 48_000, amplitude: 1)
        left[100] = .nan
        left[200] = .infinity
        right[300] = -.infinity
        var buffer = [left, right]
        p.process(buffer: &buffer)
        for channel in buffer {
            XCTAssertTrue(channel.allSatisfy { $0.isFinite && abs($0) <= 1 }, "every sample finite and within ±1")
        }

        // A limiter, not a mute: a full-scale sine boosted by 24 dB comes out just under
        // full scale (RMS of a 0.985-peak sine ≈ 0.70), not clipped and not silenced.
        let loud = try processor(settings)
        let sine = TestSignals.sine(100, sampleRate: fs, frames: 48_000, amplitude: 1)
        var sineBuffer = [sine, sine]
        loud.process(buffer: &sineBuffer)
        let tail = sineBuffer[0][24_000...]
        XCTAssertLessThanOrEqual(tail.map { abs($0) }.max()!, PeakLimiter.ceiling + 1e-6)
        XCTAssertEqual(TestSignals.rms(tail), Double(PeakLimiter.ceiling) / 2.0.squareRoot(), accuracy: 0.03)
    }

    func testBadInputDoesNotPoisonTheFilters() throws {
        let p = try processor(DSPSettings(bands: busyCurve))
        var bad = [[Float](repeating: .nan, count: 512), [Float](repeating: .infinity, count: 512)]
        p.process(buffer: &bad)
        let input = TestSignals.sine(1_000, sampleRate: fs, frames: 24_000)
        var buffer = [input, input]
        p.process(buffer: &buffer)
        XCTAssertGreaterThan(TestSignals.rms(buffer[0][12_000...]), 0.05, "audio recovers after non-finite input")
    }

    // MARK: - Sample rate and live updates

    func testSampleRateChangeRecomputesCoefficients() throws {
        let settings = DSPSettings(bands: [DSPBand(.peaking, frequency: 1_000, gainDb: 6, q: 1.41)], autoHeadroom: false)
        let p = try processor(settings)
        var warm = [TestSignals.sine(1_000, sampleRate: 48_000, frames: 9_600), TestSignals.sine(1_000, sampleRate: 48_000, frames: 9_600)]
        p.process(buffer: &warm)

        p.setSampleRate(96_000)
        XCTAssertEqual(p.plan.sampleRate, 96_000)
        XCTAssertEqual(p.latencyFrames, 480)
        let input = TestSignals.sine(1_000, sampleRate: 96_000, frames: 96_000)
        var buffer = [input, input]
        p.process(buffer: &buffer)
        XCTAssertEqual(TestSignals.gainDb(input: input, output: buffer[0]), 6, accuracy: 0.1)
        // With the 48 kHz coefficients the same band would sit near 500 Hz instead.
        let off = TestSignals.sine(2_000, sampleRate: 96_000, frames: 96_000)
        var offBuffer = [off, off]
        p.process(buffer: &offBuffer)
        XCTAssertEqual(TestSignals.gainDb(input: off, output: offBuffer[0]), p.plan.responseDb(at: [2_000])[0], accuracy: 0.1)
    }

    func testLiveCoefficientUpdateChangesTheAudio() throws {
        // ParaEQ gotcha 13: an update that "succeeds" but leaves the audio unchanged.
        let p = try processor(DSPSettings(bands: [DSPBand(.peaking, frequency: 1_000, gainDb: 0, q: 1.41)], autoHeadroom: false))
        let input = TestSignals.sine(1_000, sampleRate: fs, frames: 48_000)
        var before = [input, input]
        p.process(buffer: &before)
        XCTAssertEqual(TestSignals.gainDb(input: input, output: before[0]), 0, accuracy: 0.05)

        var settings = p.settings
        settings.bands[0].filter.gainDb = 6
        p.apply(settings)
        var after = [input, input]
        p.process(buffer: &after)
        XCTAssertEqual(TestSignals.gainDb(input: input, output: after[0]), 6, accuracy: 0.1)
    }

    func testRejectsUnsupportedFormats() {
        XCTAssertNil(DSPProcessor(settings: .flat, sampleRate: 48_000, channelCount: 0))
        XCTAssertNil(DSPProcessor(settings: .flat, sampleRate: 48_000, channelCount: DSPProcessor.maxChannels + 1))
        XCTAssertNil(DSPProcessor(settings: .flat, sampleRate: 100, channelCount: 2))
        XCTAssertNotNil(DSPProcessor(settings: .flat, sampleRate: 16_000, channelCount: 1))
    }

    func testMonoAndMultichannelStreams() throws {
        let settings = DSPSettings(bands: [DSPBand(.peaking, frequency: 1_000, gainDb: 6, q: 1.41)], autoHeadroom: false)
        for channels in [1, 6] {
            let p = try processor(settings, channels: channels)
            let input = TestSignals.sine(1_000, sampleRate: fs, frames: 24_000)
            var buffer = Array(repeating: input, count: channels)
            p.process(buffer: &buffer)
            for c in 0..<channels {
                XCTAssertEqual(TestSignals.gainDb(input: input, output: buffer[c]), 6, accuracy: 0.1, "\(channels) ch, channel \(c)")
            }
        }
    }
}
