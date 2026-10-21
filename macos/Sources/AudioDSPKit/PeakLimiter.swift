// Adapted from ParaEQ `Sources/Limiter.swift` (class `Limiter`),
// https://github.com/wabsto1/ParaEQ at 51d6f6d.
//
// MIT License
//
// Copyright (c) 2026 wabsto1
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//
// Changes for XM Audio Studio: N linked channels instead of a stereo pair, and the
// buffers are sized for the highest supported sample rate up front so a sample-rate
// change (`configure`) never allocates.

import Foundation

/// Linked lookahead peak limiter: a sliding-window minimum of the gain each sample
/// needs, instant attack (the gain is already down when the delayed peak arrives),
/// exponential release, then a hard clamp to ±1 that also turns NaN into silence.
/// One gain for all channels keeps the stereo image. Adds `lookahead` frames of
/// latency. No allocation after `init`.
final class PeakLimiter {
    static let lookaheadMs = 5.0
    static let releaseMs = 60.0
    /// About −0.13 dBFS, leaving room for the hard clamp to stay inactive normally.
    static let ceiling: Float = 0.985

    private let maxChannels: Int
    private let capacity: Int
    private(set) var lookahead = 1
    private var releaseCoef: Float = 0

    private let delay: UnsafeMutablePointer<Float>   // maxChannels × capacity
    private var delayIdx = 0

    // Monotonic deque over the lookahead window for the sliding minimum gain.
    private let dequeIdx: UnsafeMutablePointer<Int>
    private let dequeVal: UnsafeMutablePointer<Float>
    private var dequeCap = 2
    private var dqHead = 0
    private var dqCount = 0

    private var envelope: Float = 1
    private var sampleCounter = 0

    init(maxChannels: Int, maxSampleRate: Double, sampleRate: Double) {
        self.maxChannels = maxChannels
        capacity = max(1, Int(maxSampleRate * Self.lookaheadMs / 1000))
        delay = .allocate(capacity: maxChannels * capacity)
        delay.initialize(repeating: 0, count: maxChannels * capacity)
        dequeIdx = .allocate(capacity: capacity + 1)
        dequeIdx.initialize(repeating: 0, count: capacity + 1)
        dequeVal = .allocate(capacity: capacity + 1)
        dequeVal.initialize(repeating: 1, count: capacity + 1)
        configure(sampleRate: sampleRate)
    }

    deinit {
        delay.deallocate()
        dequeIdx.deallocate()
        dequeVal.deallocate()
    }

    static func lookaheadFrames(sampleRate: Double) -> Int { max(1, Int(sampleRate * lookaheadMs / 1000)) }

    /// New sample rate: resizes the window inside the preallocated buffers and clears state.
    func configure(sampleRate: Double) {
        lookahead = min(Self.lookaheadFrames(sampleRate: sampleRate), capacity)
        releaseCoef = Float(1.0 - exp(-1.0 / (Self.releaseMs / 1000 * sampleRate)))
        dequeCap = lookahead + 1
        reset()
    }

    func reset() {
        delay.update(repeating: 0, count: maxChannels * capacity)
        delayIdx = 0
        dqHead = 0
        dqCount = 0
        envelope = 1
        sampleCounter = 0
    }

    /// Planar, in place.
    func process(_ channels: UnsafePointer<UnsafeMutablePointer<Float>>, channelCount: Int, frames: Int) {
        let n = min(channelCount, maxChannels)
        let ceiling = Self.ceiling
        for i in 0..<frames {
            // Gain this incoming sample needs (linked: the loudest channel decides).
            var peak: Float = 0
            for c in 0..<n {
                let a = abs(channels[c][i])
                if a > peak || a.isNaN { peak = a.isNaN ? 0 : a }
            }
            let target: Float = peak > ceiling ? ceiling / peak : 1

            // Sliding-window minimum: drop the expired head, keep the deque increasing.
            // An entry pushed at t constrains outputs up to t + lookahead.
            while dqCount > 0, dequeIdx[dqHead] < sampleCounter - lookahead {
                dqHead = (dqHead + 1) % dequeCap
                dqCount -= 1
            }
            while dqCount > 0, dequeVal[(dqHead + dqCount - 1) % dequeCap] >= target {
                dqCount -= 1
            }
            let tail = (dqHead + dqCount) % dequeCap
            dequeIdx[tail] = sampleCounter
            dequeVal[tail] = target
            dqCount += 1
            let windowMin = dequeVal[dqHead]

            if windowMin < envelope {
                envelope = windowMin
            } else {
                envelope += releaseCoef * (windowMin - envelope)
            }

            for c in 0..<n {
                let line = delay + c * capacity
                var out = line[delayIdx] * envelope
                // NaN fails both comparisons, so it needs its own branch.
                if out > 1 { out = 1 } else if out < -1 { out = -1 } else if out.isNaN { out = 0 }
                line[delayIdx] = channels[c][i]
                channels[c][i] = out
            }
            delayIdx = (delayIdx + 1) % lookahead
            sampleCounter += 1
        }
    }
}
