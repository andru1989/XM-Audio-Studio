import XCTest
@testable import AudioStudioKit

/// `shared/autoeq-test-vectors/vectors.json`, produced by an independent Python reference.
/// The Kotlin suite (`android/core/.../eq/EqVectorsTest.kt`) runs the same file.
final class EqVectorsTests: XCTestCase {
    static let vectors: [String: Any] = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("shared/autoeq-test-vectors/vectors.json")
        let data = try! Data(contentsOf: url)
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }()

    var tolerances: [String: Double] { Self.vectors["tolerances"] as! [String: Double] }

    var model: DeviceEqModel {
        let m = Self.vectors["deviceModel"] as! [String: Any]
        return DeviceEqModel(
            bandFrequencies: m["bands"] as! [Double],
            q: m["q"] as! Double,
            stepDb: m["stepDb"] as! Double,
            minStep: m["minStep"] as! Int,
            maxStep: m["maxStep"] as! Int
        )
    }

    func testBiquadCoefficientsAndMagnitudesMatchReference() {
        for b in Self.vectors["biquads"] as! [[String: Any]] {
            let filter = ParametricFilter(
                type: FilterType(rawValue: b["type"] as! String)!,
                frequency: b["frequency"] as! Double,
                gainDb: b["gainDb"] as! Double,
                q: b["q"] as! Double
            )
            let fs = b["sampleRate"] as! Double
            let c = BiquadCoefficients(filter, sampleRate: fs)
            for (actual, expected) in zip([c.b0, c.b1, c.b2, c.a1, c.a2], b["coefficients"] as! [Double]) {
                XCTAssertEqual(actual, expected, accuracy: tolerances["coefficient"]!, "\(filter)")
            }
            for (f, expected) in zip(b["probeHz"] as! [Double], b["magnitudeDb"] as! [Double]) {
                XCTAssertEqual(c.magnitudeDb(at: f, sampleRate: fs), expected, accuracy: tolerances["magnitudeDb"]!, "\(filter) at \(f) Hz")
            }
        }
    }

    func testParsesAndFitsEveryProfile() throws {
        let grid = GraphicEqFitter.defaultGrid()
        for c in Self.vectors["fits"] as! [[String: Any]] {
            let name = c["name"] as! String
            let target: [Double]
            if let text = c["parametricText"] as? String {
                let parsed = try AutoEqParser.parseParametric(text)
                let expected = c["parsed"] as! [String: Any]
                XCTAssertEqual(parsed.preampDb, expected["preampDb"] as! Double, name)
                let filters = expected["filters"] as! [[String: Any]]
                XCTAssertEqual(parsed.filters.count, filters.count, name)
                for (e, a) in zip(filters, parsed.filters) {
                    XCTAssertEqual(a.type.rawValue, e["type"] as! String)
                    XCTAssertEqual(a.frequency, e["frequency"] as! Double)
                    XCTAssertEqual(a.gainDb, e["gainDb"] as! Double)
                    XCTAssertEqual(a.q, e["q"] as! Double)
                }
                target = parsed.responseDb(at: grid)
            } else {
                target = model.responseDb(steps: c["deviceSteps"] as! [Int], at: grid)
            }
            let result = GraphicEqFitter.fit(model: model, targetDb: target, frequencies: grid)
            let expected = c["expected"] as! [String: Any]
            XCTAssertEqual(result.steps, expected["steps"] as! [Int], name)
            for (a, e) in zip(result.continuousGainsDb, expected["continuousGainsDb"] as! [Double]) {
                XCTAssertEqual(a, e, accuracy: tolerances["fitContinuousDb"]!, "\(name) continuous")
            }
            XCTAssertEqual(result.weightedRmsErrorDb, expected["weightedRmsErrorDb"] as! Double, accuracy: tolerances["fitRmsDb"]!, name)
            XCTAssertEqual(result.levelOffsetDb, expected["levelOffsetDb"] as! Double, accuracy: tolerances["fitRmsDb"]!, name)
        }
    }

    func testGraphicEqParsingAndInterpolation() throws {
        let points = try AutoEqParser.parseGraphic("GraphicEQ: 20 -4.4; 100 -2.0; 1000 0.0; 20000 3.0")
        XCTAssertEqual(points.count, 4)
        let values = AutoEqParser.interpolate(points, at: [10, 20, 316.2277660168379, 1000, 30000])
        XCTAssertEqual(values[0], -4.4, accuracy: 1e-12)
        XCTAssertEqual(values[1], -4.4, accuracy: 1e-12)
        XCTAssertEqual(values[2], -1.0, accuracy: 1e-9)
        XCTAssertEqual(values[3], 0.0, accuracy: 1e-12)
        XCTAssertEqual(values[4], 3.0, accuracy: 1e-12)
    }

    func testRejectsMalformedProfiles() {
        for text in ["", "Preamp: -3 dB", "Filter 1: ON XYZ Fc 100 Hz Gain 1 dB Q 1", "Filter 1: ON PK Fc 0 Hz Gain 1 dB Q 1"] {
            XCTAssertThrowsError(try AutoEqParser.parseParametric(text), "should reject: '\(text)'")
        }
    }
}
