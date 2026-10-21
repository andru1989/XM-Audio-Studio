import XCTest
import CoreAudio
@testable import AudioDSPKit

/// A heap-allocated AudioBufferList with Float32 buffers, for driving the IOProc code
/// without Core Audio.
final class TestBufferList {
    let list: UnsafeMutableAudioBufferListPointer
    private var storage: [UnsafeMutablePointer<Float>] = []

    /// `layout` is the channel count of each buffer (interleaved when > 1).
    init(layout: [Int], frames: Int) {
        list = AudioBufferList.allocate(maximumBuffers: layout.count)
        for (i, channels) in layout.enumerated() {
            let data = UnsafeMutablePointer<Float>.allocate(capacity: channels * frames)
            data.initialize(repeating: 0, count: channels * frames)
            storage.append(data)
            list[i] = AudioBuffer(mNumberChannels: UInt32(channels), mDataByteSize: UInt32(channels * frames * 4), mData: data)
        }
    }

    deinit {
        storage.forEach { $0.deallocate() }
        free(list.unsafeMutablePointer)
    }

    func samples(buffer: Int) -> UnsafeMutablePointer<Float> { storage[buffer] }
    var pointer: UnsafeMutablePointer<AudioBufferList> { list.unsafeMutablePointer }
}

final class TapIOTests: XCTestCase {
    private func planar(channels: Int, frames: Int) -> (UnsafeMutablePointer<UnsafeMutablePointer<Float>>, () -> Void) {
        let table = UnsafeMutablePointer<UnsafeMutablePointer<Float>>.allocate(capacity: channels)
        for c in 0..<channels {
            let p = UnsafeMutablePointer<Float>.allocate(capacity: frames)
            p.initialize(repeating: -99, count: frames)
            (table + c).initialize(to: p)
        }
        return (table, {
            for c in 0..<channels { table[c].deallocate() }
            table.deallocate()
        })
    }

    func testStagesInterleavedTapAfterTheDeviceInputs() {
        let frames = 64
        // Buffer 0: the output device's own (mono) input, buffer 1: interleaved stereo tap.
        let input = TestBufferList(layout: [1, 2], frames: frames)
        for f in 0..<frames {
            input.samples(buffer: 0)[f] = 9
            input.samples(buffer: 1)[2 * f] = Float(f)
            input.samples(buffer: 1)[2 * f + 1] = -Float(f)
        }
        let (dst, free) = planar(channels: 2, frames: 128)
        defer { free() }
        let n = TapStaging.stage(input.list, skipBuffers: 1, into: dst, channels: 2, maxFrames: 128)
        XCTAssertEqual(n, frames)
        for f in 0..<frames {
            XCTAssertEqual(dst[0][f], Float(f))
            XCTAssertEqual(dst[1][f], -Float(f))
        }
    }

    func testStagesPlanarTapAndDuplicatesMono() {
        let input = TestBufferList(layout: [1], frames: 32)
        for f in 0..<32 { input.samples(buffer: 0)[f] = Float(f) / 32 }
        let (dst, free) = planar(channels: 2, frames: 32)
        defer { free() }
        XCTAssertEqual(TapStaging.stage(input.list, skipBuffers: 0, into: dst, channels: 2, maxFrames: 32), 32)
        for f in 0..<32 { XCTAssertEqual(dst[1][f], dst[0][f]) }
        // Nothing after the skipped buffers: no tap data.
        XCTAssertEqual(TapStaging.stage(input.list, skipBuffers: 1, into: dst, channels: 2, maxFrames: 32), 0)
    }

    func testWritesEveryOutputLayout() {
        let frames = 16
        let (src, free) = planar(channels: 2, frames: frames)
        defer { free() }
        for f in 0..<frames { src[0][f] = 0.5; src[1][f] = -0.25 }

        let interleaved = TestBufferList(layout: [2], frames: frames + 4)
        TapStaging.write(src, channels: 2, frames: frames, to: interleaved.list)
        XCTAssertEqual(interleaved.samples(buffer: 0)[0], 0.5)
        XCTAssertEqual(interleaved.samples(buffer: 0)[1], -0.25)
        XCTAssertEqual(interleaved.samples(buffer: 0)[2 * frames], 0, "frames past the processed ones are zero")

        let quad = TestBufferList(layout: [1, 1, 1, 1], frames: frames)
        TapStaging.write(src, channels: 2, frames: frames, to: quad.list)
        XCTAssertEqual((0..<4).map { quad.samples(buffer: $0)[3] }, [0.5, -0.25, 0.5, -0.25])

        let mono = TestBufferList(layout: [1], frames: frames)
        TapStaging.write(src, channels: 2, frames: frames, to: mono.list)
        XCTAssertEqual(mono.samples(buffer: 0)[5], 0.125, "stereo onto a mono output is mixed down")
    }

    func testIOContextPassesAudioThroughTheChain() throws {
        let frames = 512
        let processor = try XCTUnwrap(DSPProcessor(settings: DSPSettings(isBypassed: true), sampleRate: 48_000, channelCount: 2))
        let context = TapIOContext(processor: processor, skipInputBuffers: 0)
        let input = TestBufferList(layout: [2], frames: frames)
        let output = TestBufferList(layout: [2], frames: frames)
        let noise = TestSignals.noise(frames: 2 * frames, amplitude: 0.7)
        for i in 0..<(2 * frames) { input.samples(buffer: 0)[i] = noise[i] }
        context.render(input: UnsafePointer(input.pointer), output: output.pointer)
        for i in 0..<(2 * frames) { XCTAssertEqual(output.samples(buffer: 0)[i], noise[i]) }
        XCTAssertEqual(context.callbacks.load(ordering: .relaxed), 1)
        XCTAssertEqual(Float(bitPattern: context.inputPeakBits.load(ordering: .relaxed)), noise.map { abs($0) }.max()!)
    }

    func testDeviceListingIsReadOnlyAndHidesOurAggregate() {
        // Runs against the real HAL but only reads; there may be no devices on CI.
        for device in AudioOutputDevices.outputDevices() {
            XCTAssertFalse(device.uid.hasPrefix(AudioOutputDevices.ownAggregatePrefix))
            XCTAssertGreaterThan(device.outputChannels, 0)
        }
    }

    @MainActor
    func testEngineIsOffUntilStarted() {
        let engine = ProcessTapEngine()
        XCTAssertEqual(engine.state, .off)
        XCTAssertNil(engine.outputUID)
        XCTAssertNil(engine.takeInputPeak())
        engine.stop()
        XCTAssertEqual(engine.state, .off)
    }
}
