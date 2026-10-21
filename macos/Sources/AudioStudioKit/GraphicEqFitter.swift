import Foundation

/// How we *model* a headphone's built-in graphic EQ to predict its curve.
///
/// Sony does not publish the filter shapes; each band is modelled as an RBJ peaking
/// filter at the band centre. Curves from this model are predictions and are labelled as
/// such in both UIs (`shapeVerified` stays false until a measurement says otherwise).
public struct DeviceEqModel: Equatable, Sendable {
    public var bandFrequencies: [Double]
    public var q: Double
    /// dB per device step (unverified for XM6, assumed 1.0).
    public var stepDb: Double
    public var minStep: Int
    public var maxStep: Int
    public var shapeVerified: Bool

    public init(bandFrequencies: [Double], q: Double, stepDb: Double, minStep: Int, maxStep: Int, shapeVerified: Bool = false) {
        self.bandFrequencies = bandFrequencies
        self.q = q
        self.stepDb = stepDb
        self.minStep = minStep
        self.maxStep = maxStep
        self.shapeVerified = shapeVerified
    }

    public var bandCount: Int { bandFrequencies.count }

    public func filters(steps: [Int]) -> [ParametricFilter] {
        zip(bandFrequencies, steps).map { ParametricFilter(type: .peaking, frequency: $0, gainDb: Double($1) * stepDb, q: q) }
    }

    /// Predicted device curve for `steps` (device units).
    public func responseDb(steps: [Int], at frequencies: [Double], sampleRate: Double = ParametricProfile.defaultSampleRate) -> [Double] {
        ParametricProfile(preampDb: 0, filters: filters(steps: steps)).responseDb(at: frequencies, sampleRate: sampleRate)
    }
}

public struct FitResult: Equatable, Sendable {
    /// Integer device steps, low band first, within the model's range.
    public var steps: [Int]
    /// Unquantised least-squares solution in dB (before rounding to device steps).
    public var continuousGainsDb: [Double]
    /// Constant level shift absorbed by the fit (not sent to the headphones).
    public var levelOffsetDb: Double
    public var frequencies: [Double]
    public var targetDb: [Double]
    public var predictedDb: [Double]
    /// Weighted RMS of (predicted + offset - target).
    public var weightedRmsErrorDb: Double
    /// Largest |error| between 20 Hz and 10 kHz.
    public var maxAbsErrorDb: Double
}

/// Fits a target correction curve onto a fixed-band graphic EQ. A line-for-line port of
/// `android/core/.../eq/GraphicEqFitter.kt`; see docs/dsp/AUTOEQ.md.
///
/// 1. Continuous: box-constrained ridge least squares by cyclic coordinate descent on a
///    linearised basis (each band's dB response at +1 dB), with a free level offset.
/// 2. Discrete: round to device steps, then greedy ±1 step search on the exact response.
public enum GraphicEqFitter {
    public static let defaultRegularization = 0.02
    public static let gridPoints = 240
    private static let sweeps = 500
    private static let tolerance = 1e-10

    /// 0.5 below 40 Hz, 1.0 up to 6 kHz, then falling linearly in log f to 0.3 at 10 kHz.
    public static func defaultWeight(_ frequency: Double) -> Double {
        if frequency < 40 { return 0.5 }
        if frequency <= 6_000 { return 1.0 }
        if frequency >= 10_000 { return 0.3 }
        return 1.0 - 0.7 * (log10(frequency / 6_000) / log10(10_000.0 / 6_000))
    }

    public static func defaultGrid() -> [Double] {
        FrequencyGrid.logSpaced(min: 20, max: 20_000, count: gridPoints)
    }

    public static func fit(
        model: DeviceEqModel,
        targetDb: [Double],
        frequencies: [Double],
        regularization: Double = defaultRegularization,
        sampleRate: Double = ParametricProfile.defaultSampleRate
    ) -> FitResult {
        precondition(targetDb.count == frequencies.count)
        let n = frequencies.count
        let bands = model.bandCount
        let weights = frequencies.map(defaultWeight)
        let weightSum = weights.reduce(0, +)

        let basis: [[Double]] = (0..<bands).map { k in
            ParametricProfile(preampDb: 0, filters: [ParametricFilter(type: .peaking, frequency: model.bandFrequencies[k], gainDb: 1, q: model.q)])
                .responseDb(at: frequencies, sampleRate: sampleRate)
        }
        let minDb = Double(model.minStep) * model.stepDb
        let maxDb = Double(model.maxStep) * model.stepDb

        var x = [Double](repeating: 0, count: bands)
        var offset = 0.0
        var current = [Double](repeating: 0, count: n) // sum_k x_k * basis_k
        for _ in 0..<sweeps {
            var maxChange = 0.0
            var acc = 0.0
            for i in 0..<n { acc += weights[i] * (targetDb[i] - current[i]) }
            let newOffset = acc / weightSum
            maxChange = max(maxChange, abs(newOffset - offset))
            offset = newOffset
            for k in 0..<bands {
                let b = basis[k]
                var num = 0.0
                var den = regularization
                for i in 0..<n {
                    let partial = current[i] - x[k] * b[i]
                    num += weights[i] * b[i] * (targetDb[i] - offset - partial)
                    den += weights[i] * b[i] * b[i]
                }
                let updated = min(max(num / den, minDb), maxDb)
                let delta = updated - x[k]
                if delta != 0 {
                    for i in 0..<n { current[i] += delta * b[i] }
                    x[k] = updated
                }
                maxChange = max(maxChange, abs(delta))
            }
            if maxChange < tolerance { break }
        }

        // floor(v + 0.5): identical tie-breaking to the Kotlin port.
        var steps = x.map { min(max(Int(floor($0 / model.stepDb + 0.5)), model.minStep), model.maxStep) }
        var best = objective(model, steps, targetDb, frequencies, weights, regularization, sampleRate)
        var improved = true
        var passes = 0
        while improved && passes < 100 {
            improved = false
            passes += 1
            for k in 0..<bands {
                for delta in [-1, 1] {
                    let candidate = steps[k] + delta
                    guard candidate >= model.minStep, candidate <= model.maxStep else { continue }
                    let original = steps[k]
                    steps[k] = candidate
                    let value = objective(model, steps, targetDb, frequencies, weights, regularization, sampleRate)
                    if value < best - 1e-12 {
                        best = value
                        improved = true
                    } else {
                        steps[k] = original
                    }
                }
            }
        }

        let predicted = model.responseDb(steps: steps, at: frequencies, sampleRate: sampleRate)
        let finalOffset = weightedMean(targetDb, predicted, weights)
        var weightedSq = 0.0
        var maxAbs = 0.0
        for i in 0..<n {
            let e = predicted[i] + finalOffset - targetDb[i]
            weightedSq += weights[i] * e * e
            if (20.0...10_000.0).contains(frequencies[i]) { maxAbs = max(maxAbs, abs(e)) }
        }
        return FitResult(
            steps: steps,
            continuousGainsDb: x,
            levelOffsetDb: finalOffset,
            frequencies: frequencies,
            targetDb: targetDb,
            predictedDb: predicted,
            weightedRmsErrorDb: sqrt(weightedSq / weightSum),
            maxAbsErrorDb: maxAbs
        )
    }

    private static func objective(
        _ model: DeviceEqModel, _ steps: [Int], _ target: [Double], _ frequencies: [Double],
        _ weights: [Double], _ regularization: Double, _ sampleRate: Double
    ) -> Double {
        let predicted = model.responseDb(steps: steps, at: frequencies, sampleRate: sampleRate)
        let offset = weightedMean(target, predicted, weights)
        var sum = 0.0
        for i in 0..<predicted.count {
            let e = predicted[i] + offset - target[i]
            sum += weights[i] * e * e
        }
        for s in steps { sum += regularization * (Double(s) * model.stepDb) * (Double(s) * model.stepDb) }
        return sum
    }

    private static func weightedMean(_ target: [Double], _ predicted: [Double], _ weights: [Double]) -> Double {
        var acc = 0.0, ws = 0.0
        for i in 0..<target.count {
            acc += weights[i] * (target[i] - predicted[i])
            ws += weights[i]
        }
        return acc / ws
    }
}
