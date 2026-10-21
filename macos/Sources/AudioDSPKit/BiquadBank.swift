// Adapted from ParaEQ `Sources/BiquadEQ.swift` (class `BiquadEQ`),
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
// Changes for XM Audio Studio: any channel count instead of a fixed stereo pair, a
// fixed section count (unused sections are unity) so band edits never rebuild the
// setup, coefficients come from a precomputed matrix, the cascade runs in double
// precision (float32 low-frequency/high-Q sections leave ~1e-4 of roundoff), and
// every call that touches the setup is made from the render thread (vDSP setups are
// not thread-safe).

import Accelerate

/// Multichannel cascaded biquads on `vDSP_biquadmD` (double precision). Not thread-safe: create it on any
/// thread, then call everything else from the render thread only.
final class BiquadBank {
    let channelCount: Int
    let sectionCount: Int
    private let setup: vDSP_biquadm_SetupD

    // Preallocated channel-pointer tables: building Swift arrays per call would
    // allocate on the audio thread.
    private let inPtrs: UnsafeMutablePointer<UnsafePointer<Double>>
    private let outPtrs: UnsafeMutablePointer<UnsafeMutablePointer<Double>>
    private let ptrScratch: UnsafeMutablePointer<Double>

    /// `coefficients` holds `5 · sections · channels` doubles in vDSP_biquadm order.
    init?(coefficients: UnsafePointer<Double>, sections: Int, channels: Int) {
        guard sections > 0, channels > 0,
              let setup = vDSP_biquadm_CreateSetupD(coefficients, vDSP_Length(sections), vDSP_Length(channels))
        else { return nil }
        self.setup = setup
        sectionCount = sections
        channelCount = channels
        ptrScratch = .allocate(capacity: 1)
        ptrScratch.initialize(to: 0)
        inPtrs = .allocate(capacity: channels)
        inPtrs.initialize(repeating: UnsafePointer(ptrScratch), count: channels)
        outPtrs = .allocate(capacity: channels)
        outPtrs.initialize(repeating: ptrScratch, count: channels)
    }

    deinit {
        vDSP_biquadm_DestroySetupD(setup)
        inPtrs.deallocate()
        outPtrs.deallocate()
        ptrScratch.deallocate()
    }

    /// Glitch-free live update: vDSP ramps the running coefficients toward the
    /// targets sample by sample. Linear moves inside the (convex) stability
    /// triangle keep every intermediate filter stable.
    func rampTo(_ coefficients: UnsafePointer<Double>) {
        // Argument order is (start_section, start_channel, n_sections, n_channels);
        // counts in the offset slots silently update nothing (ParaEQ gotcha 13).
        vDSP_biquadm_SetTargetsDoubleD(setup, coefficients, 0.005, 0.05,
                                      0, 0, vDSP_Length(sectionCount), vDSP_Length(channelCount))
    }

    /// Immediate update, for a sample-rate change (the old state is meaningless anyway).
    func set(_ coefficients: UnsafePointer<Double>) {
        vDSP_biquadm_SetCoefficientsDoubleD(setup, coefficients,
                                           0, 0, vDSP_Length(sectionCount), vDSP_Length(channelCount))
    }

    func resetState() {
        vDSP_biquadm_ResetStateD(setup)
    }

    /// Planar, out of place: `input[c]` → `output[c]` for every channel.
    func process(input: UnsafePointer<UnsafeMutablePointer<Double>>,
                 output: UnsafePointer<UnsafeMutablePointer<Double>>, frames: Int) {
        for c in 0..<channelCount {
            inPtrs[c] = UnsafePointer(input[c])
            outPtrs[c] = output[c]
        }
        vDSP_biquadmD(setup, inPtrs, 1, outPtrs, 1, vDSP_Length(frames))
    }
}
