import XCTest
@testable import AudioStudioKit
import SonyHeadphonesKit

/// Same cases as `android/core/.../presets/ProfileTest.kt`, against the same shared files.
final class ProfileTests: XCTestCase {
    static let shared = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("shared")

    var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("xmas-profiles-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tempDir)
    }

    func testBuiltinsEqualTheSharedFile() throws {
        let data = try Data(contentsOf: Self.shared.appendingPathComponent("preset-schema/builtin-profiles.json"))
        XCTAssertEqual(try ProfileCodec.decode(data), BuiltinProfiles.all)
        for p in BuiltinProfiles.all { XCTAssertNil(ProfileCodec.validate(p), p.name) }
    }

    func testExampleExportDecodesAndRoundTrips() throws {
        let data = try Data(contentsOf: Self.shared.appendingPathComponent("preset-schema/examples/export-v1.json"))
        let profiles = try ProfileCodec.decode(data)
        XCTAssertEqual(profiles.count, 2)
        XCTAssertEqual(profiles[1].category, .measured)
        XCTAssertEqual(profiles[1].sourceCurve?.filters.count, 3)
        XCTAssertEqual(try ProfileCodec.decode(ProfileCodec.encode(profiles, exportedAt: "2026-10-21T12:00:00Z")), profiles)
    }

    func testEveryInvalidExampleIsRejectedWithAReason() throws {
        let dir = Self.shared.appendingPathComponent("preset-schema/examples/invalid")
        let files = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil).filter { $0.pathExtension == "json" }
        XCTAssertGreaterThanOrEqual(files.count, 7)
        for f in files {
            XCTAssertThrowsError(try ProfileCodec.decode(Data(contentsOf: f)), f.lastPathComponent) { error in
                XCTAssertFalse((error as? ProfileFormatError)?.message.isEmpty ?? true, f.lastPathComponent)
            }
        }
    }

    private func library() -> ProfileLibrary {
        var n = 0
        return ProfileLibrary(store: ProfileStore(url: tempDir.appendingPathComponent("profiles.json")),
                              now: { "2026-10-21T00:00:0\(n)Z" }, newId: { n += 1; return "id-\(n)" })
    }

    func testLibraryPersistsCreatesDuplicatesRenamesAndDeletes() throws {
        let lib = library()
        let saved = try lib.saveCurve(name: "Mi curva", modelId: "wh-1000xm6", steps: [1, 1, 0, 0, 0, 0, 0, 1, 1, 0])
        let copy = try lib.duplicate("builtin.rock", name: "Rock (copia)")
        XCTAssertEqual(copy.category, .user)
        XCTAssertEqual(copy.deviceEq, BuiltinProfiles.all.first { $0.id == "builtin.rock" }!.deviceEq)
        try lib.rename(saved.id, to: "Mi curva 2")
        try lib.updateSteps(copy.id, steps: Array(repeating: 2, count: 10))

        let reopened = ProfileLibrary(store: ProfileStore(url: tempDir.appendingPathComponent("profiles.json")))
        XCTAssertEqual(reopened.userProfiles.map(\.name), ["Mi curva 2", "Rock (copia)"])
        XCTAssertEqual(reopened.userProfiles[1].deviceEq.steps, Array(repeating: 2, count: 10))
        XCTAssertEqual(reopened.forModel("wh-1000xm6").count, BuiltinProfiles.all.count + 2)

        try lib.delete(saved.id)
        XCTAssertEqual(ProfileLibrary(store: ProfileStore(url: tempDir.appendingPathComponent("profiles.json"))).userProfiles.count, 1)
        XCTAssertThrowsError(try lib.delete("builtin.flat"))
        XCTAssertThrowsError(try lib.rename("builtin.flat", to: "x"))
        XCTAssertThrowsError(try lib.saveCurve(name: "x", modelId: "wh-1000xm6", steps: Array(repeating: 7, count: 10)))
    }

    func testImportNeverOverwritesExistingProfiles() throws {
        let lib = library()
        let exported = try lib.exportData(ids: ["builtin.gaming"])
        let first = try lib.importData(exported)
        let second = try lib.importData(exported)
        XCTAssertNotEqual(first[0].id, second[0].id)
        XCTAssertTrue(first[0].id.hasPrefix("id-"))
        XCTAssertEqual(lib.userProfiles.count, 2)
    }

    func testCorruptStoreIsPreservedNotOverwritten() throws {
        let url = tempDir.appendingPathComponent("profiles.json")
        try Data("{ not json".utf8).write(to: url)
        XCTAssertEqual(ProfileStore(url: url).load(), [])
        let names = try FileManager.default.contentsOfDirectory(atPath: tempDir.path)
        XCTAssertTrue(names.contains { $0.hasPrefix("profiles.json.corrupt-") })
    }

    func testAutoEqImportMatchesSharedVectors() throws {
        let fits = EqVectorsTests.vectors["fits"] as! [[String: Any]]
        for c in fits {
            guard let text = c["parametricText"] as? String else { continue }
            let result = try AutoEqImporter.fit(text, model: .wh1000xm6)
            XCTAssertEqual(result.fit.steps, (c["expected"] as! [String: Any])["steps"] as! [Int], c["name"] as! String)
            XCTAssertNotNil(result.sourceCurve)
        }
        let graphic = try AutoEqImporter.fit("GraphicEQ: 20 0; 1000 0; 2000 -3; 4000 -3; 8000 0; 20000 0", model: .wh1000xm6)
        XCTAssertNil(graphic.sourceCurve)
        XCTAssertTrue(graphic.fit.steps[6] < 0 || graphic.fit.steps[7] < 0)
        XCTAssertThrowsError(try AutoEqImporter.fit("nonsense", model: .wh1000xm6))
    }
}
