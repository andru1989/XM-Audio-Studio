import XCTest
import Darwin
@testable import AudioDSPKit

/// Counts heap allocations made by one thread through libmalloc's `malloc_logger`
/// hook (the one Instruments uses). Every malloc/calloc/realloc in the process calls
/// it; only the measuring thread's allocations inside `measure` are counted.
private enum AllocationCounter {
    typealias Logger = @convention(c) (UInt32, UInt, UInt, UInt, UInt, UInt32) -> Void

    /// [0] thread id, [1] counting flag, [2] allocations.
    static let probe: UnsafeMutablePointer<UInt> = {
        let p = UnsafeMutablePointer<UInt>.allocate(capacity: 3)
        p.initialize(repeating: 0, count: 3)
        return p
    }()

    static let slot: UnsafeMutablePointer<Logger?>? = {
        guard let symbol = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "malloc_logger") else { return nil } // RTLD_DEFAULT
        return symbol.assumingMemoryBound(to: Logger?.self)
    }()

    static let hook: Logger = { type, _, _, _, _, _ in
        // MALLOC_LOG_TYPE_ALLOCATE = 2 (realloc reports allocate | deallocate).
        if probe[1] == 1, type & 2 != 0, UInt(bitPattern: pthread_self()) == probe[0] {
            probe[2] &+= 1
        }
    }

    /// Nil when the hook cannot be installed (another logger active, symbol missing).
    static func measure(_ body: () -> Void) -> Int? {
        guard let slot, slot.pointee == nil else { return nil }
        _ = probe
        probe[2] = 0
        probe[0] = UInt(bitPattern: pthread_self())
        slot.pointee = hook
        probe[1] = 1
        body()
        probe[1] = 0
        slot.pointee = nil
        return Int(probe[2])
    }
}

final class RealtimeAllocationTests: XCTestCase {
    func testRenderPathDoesNotAllocate() throws {
        // The hook itself must see a deliberate allocation, or the test proves nothing.
        var sink: [Int] = []
        guard let sanity = AllocationCounter.measure({ sink = Array(repeating: 7, count: 256) }) else {
            throw XCTSkip("malloc_logger hook unavailable")
        }
        XCTAssertEqual(sink.count, 256)
        guard sanity > 0 else { throw XCTSkip("malloc_logger hook did not observe allocations on this system") }

        let frames = 512
        let settings = DSPSettings(bands: [
            DSPBand(.lowShelf, frequency: 105, gainDb: 6, q: 0.7),
            DSPBand(.peaking, frequency: 1_000, gainDb: 9, q: 1.41, channels: .left),
            DSPBand(.highShelf, frequency: 10_000, gainDb: -4, q: 0.7),
        ], preampDb: 9, autoHeadroom: false)
        let processor = try XCTUnwrap(DSPProcessor(settings: settings, sampleRate: 48_000, channelCount: 2))
        let context = TapIOContext(processor: processor, skipInputBuffers: 1)
        let input = TestBufferList(layout: [1, 2], frames: frames)
        let output = TestBufferList(layout: [2], frames: frames)
        let noise = TestSignals.noise(frames: 2 * frames, amplitude: 1.5)
        for i in 0..<(2 * frames) { input.samples(buffer: 1)[i] = noise[i] }

        // Warm-up (lazy runtime metadata, first-touch paths).
        for _ in 0..<8 { context.render(input: UnsafePointer(input.pointer), output: output.pointer) }

        var total = 0
        for i in 0..<60 {
            // Control-side changes happen outside the measured region (they may allocate),
            // their pickup by the render thread happens inside it.
            var next = settings
            next.bands[1].filter.gainDb = Double(i % 12)
            next.isBypassed = i % 10 == 5
            next.autoHeadroom = i % 3 == 0
            processor.apply(next)
            if i == 30 { processor.setSampleRate(44_100) }
            let count = try XCTUnwrap(AllocationCounter.measure {
                // A `while`, not `for … in`: this test file is unoptimized, and an
                // unoptimized range loop allocates by itself.
                var n = 0
                while n < 4 {
                    context.render(input: UnsafePointer(input.pointer), output: output.pointer)
                    n += 1
                }
            })
            total += count
        }
        XCTAssertEqual(total, 0, "allocations on the render path")
        XCTAssertTrue((0..<(2 * frames)).allSatisfy { abs(output.samples(buffer: 0)[$0]) <= 1 })
    }
}
