package io.github.andru1989.xmaudiostudio.core.presets

import io.github.andru1989.xmaudiostudio.core.SharedFixtures
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.int
import io.github.andru1989.xmaudiostudio.core.str
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun builtinsEqualTheSharedFile() {
        val shared = ProfileCodec.decode(SharedFixtures.file("preset-schema/builtin-profiles.json").readText())
        assertEquals(shared, BuiltinProfiles.all)
        BuiltinProfiles.all.forEach { assertEquals(it.name, null, ProfileCodec.validate(it)) }
    }

    @Test
    fun exampleExportDecodesAndRoundTrips() {
        val profiles = ProfileCodec.decode(SharedFixtures.file("preset-schema/examples/export-v1.json").readText())
        assertEquals(2, profiles.size)
        assertEquals(ProfileCategory.MEASURED, profiles[1].category)
        assertEquals(3, profiles[1].sourceCurve?.filters?.size)
        assertEquals(profiles, ProfileCodec.decode(ProfileCodec.encode(profiles, "2026-10-21T12:00:00Z")))
    }

    @Test
    fun everyInvalidExampleIsRejectedWithAReason() {
        val dir = SharedFixtures.file("preset-schema/examples/invalid")
        val files = dir.listFiles()!!.filter { it.extension == "json" }
        assertTrue(files.size >= 7)
        for (f in files) {
            val error = runCatching { ProfileCodec.decode(f.readText()) }.exceptionOrNull()
            assertTrue("${f.name} should be rejected", error is ProfileFormatException)
            assertTrue("${f.name} needs a message", !error!!.message.isNullOrBlank())
        }
    }

    private fun library(): ProfileLibrary {
        var n = 0
        return ProfileLibrary(ProfileStore(tmp.root.resolve("profiles.json")), now = { "2026-10-21T00:00:0${n}Z" }, newId = { "id-${++n}" })
    }

    @Test
    fun libraryPersistsCreatesDuplicatesRenamesAndDeletes() {
        val lib = library()
        val saved = lib.saveCurve("Mi curva", "wh-1000xm6", listOf(1, 1, 0, 0, 0, 0, 0, 1, 1, 0))
        val copy = lib.duplicate("builtin.rock", "Rock (copia)")
        assertEquals(ProfileCategory.USER, copy.category)
        assertEquals(BuiltinProfiles.all.first { it.id == "builtin.rock" }.deviceEq, copy.deviceEq)
        lib.rename(saved.id, "Mi curva 2")
        lib.updateSteps(copy.id, List(10) { 2 })

        val reopened = ProfileLibrary(ProfileStore(tmp.root.resolve("profiles.json")))
        assertEquals(listOf("Mi curva 2", "Rock (copia)"), reopened.userProfiles.map { it.name })
        assertEquals(List(10) { 2 }, reopened.userProfiles[1].deviceEq.steps)
        assertEquals(BuiltinProfiles.all.size + 2, reopened.forModel("wh-1000xm6").size)

        lib.delete(saved.id)
        assertEquals(1, ProfileLibrary(ProfileStore(tmp.root.resolve("profiles.json"))).userProfiles.size)
        assertTrue(runCatching { lib.delete("builtin.flat") }.isFailure)
        assertTrue(runCatching { lib.rename("builtin.flat", "x") }.isFailure)
        assertTrue("out-of-range steps are refused", runCatching { lib.saveCurve("x", "wh-1000xm6", List(10) { 7 }) }.isFailure)
    }

    @Test
    fun fiveBandModelsKeepClearBassAndOtherModelsRefuseIt() {
        val lib = library()
        val saved = lib.saveCurve("XM4", "wh-1000xm4", listOf(1, 0, -3, 2, 10), clearBass = 3)
        assertEquals(3, saved.deviceEq.clearBass)
        lib.updateSteps(saved.id, listOf(0, 0, 0, 0, 1), clearBass = -10)
        assertEquals(-10, ProfileLibrary(ProfileStore(tmp.root.resolve("profiles.json"))).userProfiles.single().deviceEq.clearBass)
        assertTrue("XM6 has no Clear Bass", runCatching { lib.saveCurve("x", "wh-1000xm6", List(10) { 0 }, clearBass = 1) }.isFailure)
        assertTrue("five bands on a 10-band model", runCatching { lib.saveCurve("x", "wh-1000xm6", List(5) { 0 }) }.isFailure)
        assertTrue("Clear Bass range", runCatching { lib.saveCurve("x", "wh-1000xm5", List(5) { 0 }, clearBass = 11) }.isFailure)
        // The AutoEQ fit works for any band count.
        assertEquals(5, AutoEqImporter.fit("GraphicEQ: 20 0; 1000 0; 2000 -3; 4000 -3; 8000 0; 20000 0", HeadphoneModel.WH_1000XM5).fit.steps.size)
    }

    @Test
    fun importNeverOverwritesExistingProfiles() {
        val lib = library()
        val exported = lib.exportText(listOf("builtin.gaming"))
        val first = lib.importText(exported)
        val second = lib.importText(exported)
        assertNotEquals(first.single().id, second.single().id)
        assertTrue(first.single().id.startsWith("id-"))
        assertEquals(2, lib.userProfiles.size)
    }

    @Test
    fun corruptStoreIsPreservedNotOverwritten() {
        val file = tmp.root.resolve("profiles.json").apply { writeText("{ not json") }
        assertEquals(emptyList<EqProfile>(), ProfileStore(file).load())
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("profiles.json.corrupt-") })
    }

    @Test
    fun autoEqImportMatchesSharedVectors() {
        val fits = SharedFixtures.json("autoeq-test-vectors/vectors.json")["fits"]!!.jsonArray.map { it.jsonObject }
        for (case in fits.filter { it.containsKey("parametricText") }) {
            val result = AutoEqImporter.fit(case["parametricText"]!!.str, HeadphoneModel.WH_1000XM6)
            val expected = case["expected"]!!.jsonObject["steps"]!!.jsonArray.map { it.int }
            assertEquals(case["name"]!!.str, expected, result.fit.steps)
            assertNotNull(result.sourceCurve)
        }
        val graphic = AutoEqImporter.fit("GraphicEQ: 20 0; 1000 0; 2000 -3; 4000 -3; 8000 0; 20000 0", HeadphoneModel.WH_1000XM6)
        assertEquals(null, graphic.sourceCurve)
        assertTrue(graphic.fit.steps[6] < 0 || graphic.fit.steps[7] < 0)
        assertTrue(runCatching { AutoEqImporter.fit("nonsense", HeadphoneModel.WH_1000XM6) }.exceptionOrNull() is ProfileFormatException)
    }
}
