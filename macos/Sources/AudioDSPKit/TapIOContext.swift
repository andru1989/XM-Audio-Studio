import Foundation
import CoreAudio
import Accelerate
import Synchronization

/// Moves samples between Core Audio buffer lists (Float32, interleaved or planar) and
/// the processor's planar buffers. Pure functions on pointers, so the IOProc's data
/// path is unit-tested without touching the audio hardware.
enum TapStaging {
    /// Copies the tap's channels into `dst[0..<channels]`, skipping the first
    /// `skipBuffers` input buffers (input streams that belong to the output device
    /// itself, ahead of the tap in the aggregate). Channels the tap does not deliver
    /// repeat the first one. Returns the frame count (≤ `maxFrames`), 0 when there is
    /// no tap data.
    static func stage(_ input: UnsafeMutableAudioBufferListPointer, skipBuffers: Int,
                      into dst: UnsafePointer<UnsafeMutablePointer<Float>>, channels: Int, maxFrames: Int) -> Int {
        var frames = 0
        var channel = 0
        var index = 0
        for buffer in input {
            let current = index
            index += 1
            guard current >= skipBuffers, channel < channels,
                  let data = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
            let n = max(Int(buffer.mNumberChannels), 1)
            let bufferFrames = min(Int(buffer.mDataByteSize) / (n * MemoryLayout<Float>.size), maxFrames)
            if channel == 0 { frames = bufferFrames }
            let count = min(bufferFrames, frames)
            for ch in 0..<n where channel < channels {
                let d = dst[channel]
                if n == 1 {
                    d.update(from: data, count: count)
                } else {
                    for f in 0..<count { d[f] = data[f * n + ch] }
                }
                if count < frames { (d + count).update(repeating: 0, count: frames - count) }
                channel += 1
            }
        }
        guard channel > 0, frames > 0 else { return 0 }
        while channel < channels {
            dst[channel].update(from: dst[0], count: frames)
            channel += 1
        }
        return frames
    }

    /// Writes the processed channels to every output buffer. Output channel j takes
    /// processed channel j mod `channels` (stereo onto a multichannel device repeats
    /// L/R); a stereo signal onto a single mono output is mixed down. Frames the
    /// processor did not produce are zero.
    static func write(_ src: UnsafePointer<UnsafeMutablePointer<Float>>, channels: Int, frames: Int,
                      to output: UnsafeMutableAudioBufferListPointer) {
        var totalOut = 0
        for buffer in output { totalOut += Int(buffer.mNumberChannels) }
        let downmix = totalOut == 1 && channels >= 2
        var outChannel = 0
        for buffer in output {
            let n = max(Int(buffer.mNumberChannels), 1)
            guard let data = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
            let outFrames = Int(buffer.mDataByteSize) / (n * MemoryLayout<Float>.size)
            let count = min(outFrames, frames)
            for ch in 0..<n {
                if downmix {
                    let l = src[0], r = src[1]
                    for f in 0..<count { data[f * n + ch] = 0.5 * (l[f] + r[f]) }
                } else {
                    let s = src[outChannel % channels]
                    if n == 1 {
                        data.update(from: s, count: count)
                    } else {
                        for f in 0..<count { data[f * n + ch] = s[f] }
                    }
                }
                for f in count..<outFrames { data[f * n + ch] = 0 }
                outChannel += 1
            }
        }
    }

    static func silence(_ output: UnsafeMutableAudioBufferListPointer) {
        for buffer in output {
            if let data = buffer.mData { memset(data, 0, Int(buffer.mDataByteSize)) }
        }
    }
}

/// Everything the IOProc touches. Created on the main thread before the IOProc starts,
/// released after it has stopped; the render method is the only thing the IO thread calls.
final class TapIOContext: @unchecked Sendable {
    let processor: DSPProcessor
    let channels: Int
    let skipInputBuffers: Int
    private let planar: UnsafeMutablePointer<Float>
    let planarPtrs: UnsafeMutablePointer<UnsafeMutablePointer<Float>>

    /// IOProc invocations since start (watchdog for stalled aggregates).
    let callbacks = Atomic<UInt64>(0)
    /// Largest input |sample| since the UI last asked (Float bit pattern).
    let inputPeakBits = Atomic<UInt32>(0)

    init(processor: DSPProcessor, skipInputBuffers: Int) {
        self.processor = processor
        channels = processor.channelCount
        self.skipInputBuffers = skipInputBuffers
        let frames = DSPProcessor.maxFramesPerSlice
        planar = .allocate(capacity: channels * frames)
        planar.initialize(repeating: 0, count: channels * frames)
        planarPtrs = .allocate(capacity: channels)
        for c in 0..<channels { (planarPtrs + c).initialize(to: planar + c * frames) }
    }

    deinit {
        planar.deallocate()
        planarPtrs.deallocate()
    }

    /// The IOProc body: tap input → DSP chain → device output. Realtime-safe.
    func render(input: UnsafePointer<AudioBufferList>, output: UnsafeMutablePointer<AudioBufferList>) {
        callbacks.wrappingAdd(1, ordering: .relaxed)
        let inList = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: input))
        let outList = UnsafeMutableAudioBufferListPointer(output)
        let frames = TapStaging.stage(inList, skipBuffers: skipInputBuffers, into: planarPtrs,
                                      channels: channels, maxFrames: DSPProcessor.maxFramesPerSlice)
        guard frames > 0 else {
            TapStaging.silence(outList)
            return
        }
        var peak: Float = 0
        for c in 0..<channels {
            var m: Float = 0
            vDSP_maxmgv(planarPtrs[c], 1, &m, vDSP_Length(frames))
            if m > peak { peak = m }
        }
        if peak > Float(bitPattern: inputPeakBits.load(ordering: .relaxed)) {
            inputPeakBits.store(peak.bitPattern, ordering: .relaxed)
        }
        processor.process(channels: planarPtrs, frameCount: frames)
        TapStaging.write(planarPtrs, channels: channels, frames: frames, to: outList)
    }
}
