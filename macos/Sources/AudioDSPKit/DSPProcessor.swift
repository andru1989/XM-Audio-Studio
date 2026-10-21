import Foundation
import Accelerate
import os

/// The local DSP chain for one stream: preamp → parametric biquads (double precision)
/// → linked lookahead limiter (+ hard clamp at ±1), with an A/B bypass that crossfades
/// to the untouched input. Works offline (`process(buffer:)`, used by the tests) and in a Core Audio
/// IOProc (`process(channels:frameCount:)`).
///
/// Threading contract:
/// - *Control* calls (`apply`, `setSampleRate`, the read-only properties) come from one
///   thread at a time, normally the main thread.
/// - *Render* calls (`process`) come from one thread at a time, normally the IO thread.
/// - The render path never allocates, never blocks and never calls into Objective-C:
///   parameters cross over through a preallocated block guarded by an unfair lock that
///   the render side only ever *tries* (a contended block simply keeps the previous
///   parameters for one more buffer).
public final class DSPProcessor: @unchecked Sendable {
    public static let maxChannels = 8
    public static let maxFramesPerSlice = 4096
    public static let maxSampleRate = 768_000.0
    /// Length of the A/B crossfade.
    public static let bypassFadeSeconds = 0.010

    public let channelCount: Int
    public let sectionCount: Int

    // MARK: Control-side state (control thread only)

    public private(set) var settings: DSPSettings
    public private(set) var sampleRate: Double
    /// Coefficients and preamp currently published to the render side.
    public private(set) var plan: DSPCoefficientPlan

    /// Latency the chain adds (the limiter lookahead), in frames at `sampleRate`.
    public var latencyFrames: Int { PeakLimiter.lookaheadFrames(sampleRate: sampleRate) }

    private let controlCoefficients: UnsafeMutablePointer<Double>

    // MARK: Shared block (guarded by `lock`)

    private struct Shared {
        var dirty = false
        var preampLinear: Float = 1
        var bypass = false
        var sampleRate: Double = 0
    }
    private let lock: UnsafeMutablePointer<os_unfair_lock>
    private let shared: UnsafeMutablePointer<Shared>
    private let sharedCoefficients: UnsafeMutablePointer<Double>

    // MARK: Render-side state (render thread only)

    private let bank: BiquadBank
    private let limiter: PeakLimiter
    private let stage: UnsafeMutablePointer<Double>     // channelCount × maxFramesPerSlice
    private let filtered: UnsafeMutablePointer<Double>  // channelCount × maxFramesPerSlice
    private let wet: UnsafeMutablePointer<Float>        // channelCount × maxFramesPerSlice
    private let stagePtrs: UnsafeMutablePointer<UnsafeMutablePointer<Double>>
    private let filteredPtrs: UnsafeMutablePointer<UnsafeMutablePointer<Double>>
    private let wetPtrs: UnsafeMutablePointer<UnsafeMutablePointer<Float>>
    private let slicePtrs: UnsafeMutablePointer<UnsafeMutablePointer<Float>>
    private var renderSampleRate: Double
    private var preampCurrent: Float
    private var preampTarget: Float
    private var mix: Float            // 0 = dry (bypass), 1 = wet
    private var mixTarget: Float
    private var mixStep: Float

    // Offline helper buffers (`process(buffer:)`), owned by the render side too.
    private let offline: UnsafeMutablePointer<Float>
    private let offlinePtrs: UnsafeMutablePointer<UnsafeMutablePointer<Float>>

    /// Nil for an unsupported channel count or sample rate.
    public init?(settings: DSPSettings, sampleRate: Double, channelCount: Int, sectionCount: Int = DSPSettings.maxBands) {
        guard (1...Self.maxChannels).contains(channelCount), sampleRate >= 8_000, sampleRate <= Self.maxSampleRate,
              sectionCount > 0 else { return nil }
        let settings = settings.sanitized()
        self.channelCount = channelCount
        self.sectionCount = sectionCount
        self.settings = settings
        self.sampleRate = sampleRate
        let plan = DSPCoefficientPlan(settings: settings, sampleRate: sampleRate, channelCount: channelCount, sectionCount: sectionCount)
        self.plan = plan

        let count = plan.coefficientCount
        controlCoefficients = .allocate(capacity: count)
        controlCoefficients.initialize(repeating: 0, count: count)
        sharedCoefficients = .allocate(capacity: count)
        sharedCoefficients.initialize(repeating: 0, count: count)
        plan.write(into: controlCoefficients)
        guard let bank = BiquadBank(coefficients: controlCoefficients, sections: sectionCount, channels: channelCount) else {
            controlCoefficients.deallocate()
            sharedCoefficients.deallocate()
            return nil
        }
        self.bank = bank
        limiter = PeakLimiter(maxChannels: channelCount, maxSampleRate: Self.maxSampleRate, sampleRate: sampleRate)

        lock = .allocate(capacity: 1)
        lock.initialize(to: os_unfair_lock())
        shared = .allocate(capacity: 1)
        shared.initialize(to: Shared(dirty: false, preampLinear: Self.linear(plan.preampDb),
                                     bypass: settings.isBypassed, sampleRate: sampleRate))

        let slice = Self.maxFramesPerSlice
        stage = .allocate(capacity: channelCount * slice)
        stage.initialize(repeating: 0, count: channelCount * slice)
        filtered = .allocate(capacity: channelCount * slice)
        filtered.initialize(repeating: 0, count: channelCount * slice)
        wet = .allocate(capacity: channelCount * slice)
        wet.initialize(repeating: 0, count: channelCount * slice)
        offline = .allocate(capacity: channelCount * slice)
        offline.initialize(repeating: 0, count: channelCount * slice)
        stagePtrs = .allocate(capacity: channelCount)
        filteredPtrs = .allocate(capacity: channelCount)
        wetPtrs = .allocate(capacity: channelCount)
        offlinePtrs = .allocate(capacity: channelCount)
        slicePtrs = .allocate(capacity: channelCount)
        for c in 0..<channelCount {
            (stagePtrs + c).initialize(to: stage + c * slice)
            (filteredPtrs + c).initialize(to: filtered + c * slice)
            (wetPtrs + c).initialize(to: wet + c * slice)
            (offlinePtrs + c).initialize(to: offline + c * slice)
            (slicePtrs + c).initialize(to: offline + c * slice)
        }

        renderSampleRate = sampleRate
        preampCurrent = Self.linear(plan.preampDb)
        preampTarget = preampCurrent
        mix = settings.isBypassed ? 0 : 1
        mixTarget = mix
        mixStep = Self.fadeStep(sampleRate)
    }

    deinit {
        controlCoefficients.deallocate()
        sharedCoefficients.deallocate()
        lock.deinitialize(count: 1)
        lock.deallocate()
        shared.deinitialize(count: 1)
        shared.deallocate()
        stage.deallocate()
        filtered.deallocate()
        wet.deallocate()
        offline.deallocate()
        stagePtrs.deallocate()
        filteredPtrs.deallocate()
        wetPtrs.deallocate()
        offlinePtrs.deallocate()
        slicePtrs.deallocate()
    }

    private static func linear(_ db: Double) -> Float { Float(pow(10.0, db / 20.0)) }
    private static func fadeStep(_ rate: Double) -> Float { Float(1.0 / max(1.0, bypassFadeSeconds * rate)) }

    // MARK: - Control

    /// New settings, effective from the next rendered buffer. Coefficient changes ramp
    /// smoothly; bypass crossfades over `bypassFadeSeconds`.
    public func apply(_ settings: DSPSettings) {
        self.settings = settings.sanitized()
        publish(resetState: false)
    }

    /// Recomputes every coefficient and the limiter window for a new rate. Filter and
    /// limiter state restart from silence (the old state belongs to the old rate).
    public func setSampleRate(_ rate: Double) {
        guard rate >= 8_000, rate <= Self.maxSampleRate else { return }
        sampleRate = rate
        publish(resetState: true)
    }

    private func publish(resetState: Bool) {
        plan = DSPCoefficientPlan(settings: settings, sampleRate: sampleRate, channelCount: channelCount, sectionCount: sectionCount)
        plan.write(into: controlCoefficients)
        let preamp = Self.linear(plan.preampDb)
        os_unfair_lock_lock(lock)
        sharedCoefficients.update(from: controlCoefficients, count: plan.coefficientCount)
        shared.pointee.preampLinear = preamp
        shared.pointee.bypass = settings.isBypassed
        shared.pointee.sampleRate = sampleRate
        shared.pointee.dirty = true
        os_unfair_lock_unlock(lock)
    }

    // MARK: - Render

    /// Offline processing, in place: `buffer[channel][frame]`. Runs the exact realtime
    /// code path (in slices of `maxFramesPerSlice`), so tests exercise what plays.
    public func process(buffer: inout [[Float]]) {
        precondition(buffer.count == channelCount, "expected \(channelCount) channels")
        let frames = buffer.first?.count ?? 0
        precondition(buffer.allSatisfy { $0.count == frames }, "channels must have the same length")
        var start = 0
        while start < frames {
            let n = min(Self.maxFramesPerSlice, frames - start)
            for c in 0..<channelCount {
                buffer[c].withUnsafeBufferPointer { src in
                    offlinePtrs[c].update(from: src.baseAddress! + start, count: n)
                }
            }
            renderSlice(offlinePtrs, frames: n)
            for c in 0..<channelCount {
                buffer[c].withUnsafeMutableBufferPointer { dst in
                    (dst.baseAddress! + start).update(from: offlinePtrs[c], count: n)
                }
            }
            start += n
        }
    }

    /// Realtime processing, in place, planar: `channels[c]` points at `frameCount`
    /// samples for each of `channelCount` channels. Any frame count is accepted.
    public func process(channels: UnsafePointer<UnsafeMutablePointer<Float>>, frameCount: Int) {
        var start = 0
        while start < frameCount {
            let n = min(Self.maxFramesPerSlice, frameCount - start)
            for c in 0..<channelCount { slicePtrs[c] = channels[c] + start }
            renderSlice(slicePtrs, frames: n)
            start += n
        }
    }

    private func pullParameters() {
        guard os_unfair_lock_trylock(lock) else { return }
        if shared.pointee.dirty {
            let rate = shared.pointee.sampleRate
            if rate != renderSampleRate {
                bank.set(sharedCoefficients)
                bank.resetState()
                limiter.configure(sampleRate: rate)
                renderSampleRate = rate
                mixStep = Self.fadeStep(rate)
                preampCurrent = shared.pointee.preampLinear
            } else {
                bank.rampTo(sharedCoefficients)
            }
            preampTarget = shared.pointee.preampLinear
            mixTarget = shared.pointee.bypass ? 0 : 1
            shared.pointee.dirty = false
        }
        os_unfair_lock_unlock(lock)
    }

    private func renderSlice(_ io: UnsafePointer<UnsafeMutablePointer<Float>>, frames: Int) {
        guard frames > 0 else { return }
        pullParameters()

        // 1. Preamp (ramped over the slice when it changes) into the stage buffers.
        //    Anything non-finite or absurdly loud becomes silence here, so one bad
        //    sample cannot poison the IIR state for good.
        let g0 = preampCurrent
        let g1 = preampTarget
        let dg = frames > 1 ? (g1 - g0) / Float(frames) : 0
        for c in 0..<channelCount {
            let src = io[c]
            let dst = stagePtrs[c]
            var g = g0
            for i in 0..<frames {
                g = g0 == g1 ? g1 : g + dg
                var x = src[i] * g
                if !(abs(x) <= 1_000) { x = 0 }
                dst[i] = Double(x)
            }
        }
        preampCurrent = g1

        // 2. Biquad cascade, 3. limiter + clamp. Runs during bypass too, so the filters
        //    are warm when the A/B switches back.
        bank.process(input: stagePtrs, output: filteredPtrs, frames: frames)
        for c in 0..<channelCount {
            vDSP_vdpsp(filteredPtrs[c], 1, wetPtrs[c], 1, vDSP_Length(frames))
        }
        limiter.process(wetPtrs, channelCount: channelCount, frames: frames)

        // 4. Output: dry (bit-exact), wet, or a crossfade between them.
        if mix == 0 && mixTarget == 0 {
            return // `io` still holds the input untouched.
        }
        if mix == 1 && mixTarget == 1 {
            for c in 0..<channelCount { io[c].update(from: wetPtrs[c], count: frames) }
            return
        }
        let start = mix
        var m = mix
        for c in 0..<channelCount {
            m = start
            let out = io[c]
            let w = wetPtrs[c]
            for i in 0..<frames {
                if m < mixTarget { m = min(mixTarget, m + mixStep) } else if m > mixTarget { m = max(mixTarget, m - mixStep) }
                let dry = out[i]
                out[i] = m == 0 ? dry : (m == 1 ? w[i] : dry + m * (w[i] - dry))
            }
        }
        mix = m
    }
}
