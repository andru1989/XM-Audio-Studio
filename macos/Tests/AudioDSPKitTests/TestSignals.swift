import Foundation

enum TestSignals {
    static func sine(_ frequency: Double, sampleRate: Double, frames: Int, amplitude: Float = 0.25) -> [Float] {
        (0..<frames).map { amplitude * Float(sin(2 * Double.pi * frequency * Double($0) / sampleRate)) }
    }

    /// Deterministic white noise in −amplitude…amplitude.
    static func noise(frames: Int, amplitude: Float = 0.5, seed: UInt64 = 0x9E37_79B9_7F4A_7C15) -> [Float] {
        var state = seed
        return (0..<frames).map { _ in
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            let unit = Float(state >> 40) / Float(1 << 24)   // 0..<1
            return amplitude * (2 * unit - 1)
        }
    }

    static func rms(_ x: ArraySlice<Float>) -> Double {
        sqrt(x.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(max(x.count, 1)))
    }

    static func db(_ ratio: Double) -> Double { 20 * log10(ratio) }

    /// Steady-state gain of `output` over `input`, measured on the second half.
    static func gainDb(input: [Float], output: [Float]) -> Double {
        let half = input.count / 2
        return db(rms(output[half...]) / rms(input[half...]))
    }
}
