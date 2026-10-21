import XCTest
@testable import SonyHeadphonesKit

/// The in-code catalogue must equal `shared/protocol-spec/models.json` (Kotlin checks the same file).
final class CatalogConformanceTests: XCTestCase {
    func testEveryModelMatchesTheSharedCatalogue() throws {
        let doc = try ConformanceTests.fixture("protocol-spec/models.json")
        let models = try XCTUnwrap(doc["models"] as? [[String: Any]])
        XCTAssertEqual(models.count, HeadphoneModel.allCases.count)
        for m in models {
            let id = m["id"] as! String
            let model = try XCTUnwrap(HeadphoneModel(rawValue: id), id)
            XCTAssertEqual(model.displayName, m["displayName"] as? String, id)
            XCTAssertEqual(model.namePatterns, m["namePatterns"] as? [String], id)
            XCTAssertEqual(model.protocolFamily.rawValue, m["protocol"] as? String, id)
            XCTAssertEqual(model.hasAdapter, !(m["adapter"] is NSNull), id)

            let caps = DeviceCatalog.capabilities(model)
            let features = m["features"] as! [String: String]
            XCTAssertEqual(Dictionary(uniqueKeysWithValues: caps.features.map { ($0.key.rawValue, $0.value.rawValue) }), features, id)
            let range = m["ambientLevel"] as! [String: Int]
            XCTAssertEqual(caps.ambientLevelRange, range["min"]!...range["max"]!, id)

            guard let e = m["equalizer"] as? [String: Any] else {
                XCTAssertNil(caps.equalizer, id)
                continue
            }
            let spec = try XCTUnwrap(caps.equalizer, id)
            XCTAssertEqual(spec.bandCount, e["bandCount"] as? Int, id)
            XCTAssertEqual(spec.bandFrequenciesHz, (e["bandFrequenciesHz"] as! [NSNumber]).map(\.doubleValue), id)
            XCTAssertEqual(spec.frequenciesVerified, e["frequenciesVerified"] as? Bool, id)
            XCTAssertEqual(spec.minStep, e["minStep"] as? Int, id)
            XCTAssertEqual(spec.maxStep, e["maxStep"] as? Int, id)
            XCTAssertEqual(spec.wireOffset, e["wireOffset"] as? Int, id)
            XCTAssertEqual(spec.stepDb, (e["stepDb"] as! NSNumber).doubleValue, id)
            if let cb = e["clearBass"] as? [String: Int] {
                XCTAssertEqual(spec.clearBassRange, cb["minStep"]!...cb["maxStep"]!, id)
            } else {
                XCTAssertNil(spec.clearBassRange, id)
            }
            let presets = (e["presets"] as! [[String: Any]]).map { "\($0["code"] as! Int):\($0["name"] as! String)" }
            XCTAssertEqual(spec.presets.map { "\($0.code):\($0.name)" }, presets, id)
        }
    }

    func testBluetoothNamesMapToModels() {
        XCTAssertEqual(HeadphoneModel.from(bluetoothName: "WH-1000XM6"), .wh1000xm6)
        XCTAssertEqual(HeadphoneModel.from(bluetoothName: "Andrés's WH-1000XM6"), .wh1000xm6)
        XCTAssertEqual(HeadphoneModel.from(bluetoothName: "wh-1000xm4"), .wh1000xm4)
        XCTAssertEqual(HeadphoneModel.from(bluetoothName: "MDR-1000X"), .mdr1000x)
        XCTAssertNil(HeadphoneModel.from(bluetoothName: "WF-1000XM5"))
        XCTAssertNil(HeadphoneModel.from(bluetoothName: "AirPods Pro"))
        XCTAssertNil(HeadphoneModel.from(bluetoothName: nil))
    }
}
