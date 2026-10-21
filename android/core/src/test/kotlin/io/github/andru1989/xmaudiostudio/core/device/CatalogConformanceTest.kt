package io.github.andru1989.xmaudiostudio.core.device

import io.github.andru1989.xmaudiostudio.core.SharedFixtures
import io.github.andru1989.xmaudiostudio.core.bool
import io.github.andru1989.xmaudiostudio.core.double
import io.github.andru1989.xmaudiostudio.core.int
import io.github.andru1989.xmaudiostudio.core.str
import io.github.andru1989.xmaudiostudio.core.model.ProtocolVersion
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The in-code catalogue must equal shared/protocol-spec/models.json (the Swift suite checks the same). */
class CatalogConformanceTest {
    private val models = SharedFixtures.json("protocol-spec/models.json")["models"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun everyModelMatchesTheSharedCatalogue() {
        assertEquals(HeadphoneModel.entries.size, models.size)
        for (m in models) {
            val id = m["id"]!!.str
            val model = requireNotNull(HeadphoneModel.fromId(id)) { "unknown model $id" }
            assertEquals(id, m["displayName"]!!.str, model.displayName)
            assertEquals(id, m["namePatterns"]!!.jsonArray.map { it.str }, model.namePatterns)
            val protocol = when (model.protocol) {
                ProtocolFamily.MDR_V1 -> "mdr-v1"; ProtocolFamily.MDR_V2 -> "mdr-v2"; ProtocolFamily.UNKNOWN -> "unknown"
            }
            assertEquals(id, m["protocol"]!!.str, protocol)
            val adapterId = m["adapter"]!!.let { if (it is JsonNull) null else it.str }
            val adapter = ProtocolAdapter.forModel(model)
            assertEquals("$id adapter", adapterId, adapter?.id)
            if (adapter != null) {
                val expectedVersion = if (model.protocol == ProtocolFamily.MDR_V1) ProtocolVersion.V1 else ProtocolVersion.V2
                assertEquals("$id protocol family", expectedVersion, adapter.expectedProtocol)
            }

            val caps = DeviceCatalog.capabilities(model)
            val features = m["features"]!!.jsonObject.mapValues { it.value.str }
            assertEquals(id, features, caps.features.entries.associate { it.key.id to it.value.id })
            val range = m["ambientLevel"]!!.jsonObject
            assertEquals(id, range["min"]!!.int..range["max"]!!.int, caps.ambientLevelRange)

            val eq = m["equalizer"]!!
            if (eq is JsonNull) {
                assertNull(id, caps.equalizer)
                continue
            }
            val e = eq.jsonObject
            val spec = requireNotNull(caps.equalizer)
            assertEquals(id, e["bandCount"]!!.int, spec.bandCount)
            assertEquals(id, e["bandFrequenciesHz"]!!.jsonArray.map { it.double }, spec.bandFrequenciesHz)
            assertEquals(id, e["frequenciesVerified"]!!.bool, spec.frequenciesVerified)
            assertEquals(id, e["minStep"]!!.int, spec.minStep)
            assertEquals(id, e["maxStep"]!!.int, spec.maxStep)
            assertEquals(id, e["wireOffset"]!!.int, spec.wireOffset)
            assertEquals(id, e["stepDb"]!!.double, spec.stepDb, 0.0)
            val cb = e["clearBass"]!!
            assertEquals(id, if (cb is JsonNull) null else cb.jsonObject["minStep"]!!.int..cb.jsonObject["maxStep"]!!.int, spec.clearBassRange)
            assertEquals(id, e["presets"]!!.jsonArray.map { it.jsonObject["code"]!!.int to it.jsonObject["name"]!!.str },
                spec.presets.map { it.code to it.name })
        }
    }

    @Test
    fun bluetoothNamesMapToModels() {
        assertEquals(HeadphoneModel.WH_1000XM6, HeadphoneModel.fromBluetoothName("WH-1000XM6"))
        assertEquals(HeadphoneModel.WH_1000XM6, HeadphoneModel.fromBluetoothName("Andrés's WH-1000XM6"))
        assertEquals(HeadphoneModel.WH_1000XM4, HeadphoneModel.fromBluetoothName("wh-1000xm4"))
        assertEquals(HeadphoneModel.MDR_1000X, HeadphoneModel.fromBluetoothName("MDR-1000X"))
        assertNull(HeadphoneModel.fromBluetoothName("WF-1000XM5"))
        assertNull(HeadphoneModel.fromBluetoothName("AirPods Pro"))
        assertNull(HeadphoneModel.fromBluetoothName(null))
    }
}
