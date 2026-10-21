package io.github.andru1989.xmaudiostudio.core.presets

import io.github.andru1989.xmaudiostudio.core.device.DeviceCatalog
import io.github.andru1989.xmaudiostudio.core.device.EqualizerSpec
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneState
import io.github.andru1989.xmaudiostudio.core.model.EqualizerPreset
import java.io.File
import java.time.Instant
import java.util.UUID

/** User profiles as one JSON file in the shared export format. Writes are atomic. */
class ProfileStore(private val file: File) {

    fun load(): List<EqProfile> {
        if (!file.exists()) return emptyList()
        return try {
            ProfileCodec.decode(file.readText())
        } catch (e: ProfileFormatException) {
            // Keep the unreadable file for diagnosis instead of overwriting the user's data.
            file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"), overwrite = true)
            emptyList()
        }
    }

    fun save(profiles: List<EqProfile>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(ProfileCodec.encode(profiles))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not write ${file.path}" }
        }
    }
}

/**
 * Built-in plus user profiles. Unlimited profiles live here, in the app; the headphones only
 * ever hold the single curve currently sent to them.
 */
class ProfileLibrary(
    private val store: ProfileStore,
    private val now: () -> String = { Instant.now().toString() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    var userProfiles: List<EqProfile> = store.load()
        private set

    fun forModel(modelId: String): List<EqProfile> =
        BuiltinProfiles.forModel(modelId) + userProfiles.filter { it.model == modelId }

    fun find(id: String): EqProfile? = BuiltinProfiles.all.firstOrNull { it.id == id } ?: userProfiles.firstOrNull { it.id == id }

    /** Saves a custom curve as a new user profile. [clearBass] only for models that have it (XM3-XM5). */
    fun saveCurve(
        name: String,
        modelId: String,
        steps: List<Int>,
        category: ProfileCategory = ProfileCategory.USER,
        sourceCurve: SourceCurve? = null,
        source: ProfileSource? = null,
        notes: String? = null,
        clearBass: Int? = null,
    ): EqProfile {
        val stamp = now()
        val setting = DeviceEqSetting(steps = steps, clearBass = clearBass)
        val profile = EqProfile(
            id = newId(), name = name.trim(), category = category, model = modelId, deviceEq = setting,
            sourceCurve = sourceCurve, source = source, notes = notes, createdAt = stamp, updatedAt = stamp,
        )
        return add(profile)
    }

    /**
     * "Guardar configuración completa": saves what the headphones report now as one profile, the
     * EQ (custom curve, Sony preset, or the bands of a Sound Connect user setting) plus the
     * [HeadphoneScene] of every other setting the model supports and the headphones reported.
     * Throws [ProfileFormatException] when the headphones have not reported their EQ.
     */
    fun saveScene(name: String, modelId: String, state: HeadphoneState): EqProfile {
        val model = HeadphoneModel.fromId(modelId) ?: throw ProfileFormatException("unknown model '$modelId'")
        val capabilities = DeviceCatalog.capabilities(model)
        val (eq, notes) = captureEq(state, capabilities.equalizer)
            ?: throw ProfileFormatException("The headphones have not reported their equalizer yet")
        val stamp = now()
        return add(
            EqProfile(
                id = newId(), name = name.trim(), category = ProfileCategory.USER, model = modelId, deviceEq = eq,
                scene = HeadphoneScenes.capture(state, capabilities), notes = notes, createdAt = stamp, updatedAt = stamp,
            ),
        )
    }

    /** The EQ the headphones report, as a profile setting, with a note for a Sound Connect user setting. */
    private fun captureEq(state: HeadphoneState, spec: EqualizerSpec?): Pair<DeviceEqSetting, String?>? {
        val eq = state.equalizer ?: return null
        spec ?: return null
        val clearBass = eq.clearBass?.takeIf { spec.clearBassRange != null }
        return when {
            eq.presetCode == EqualizerPreset.CUSTOM.code ->
                eq.bands.takeIf { it.size == spec.bandCount }?.let { DeviceEqSetting(steps = it, clearBass = clearBass) to null }
            spec.presets.any { it.code == eq.presetCode } -> DeviceEqSetting(presetCode = eq.presetCode) to null
            // A Sound Connect user setting: keep its curve as a custom profile.
            eq.bands.size == spec.bandCount ->
                DeviceEqSetting(steps = eq.bands, clearBass = clearBass) to SOUND_CONNECT_NOTE.format(eq.presetCode)
            else -> null
        }
    }

    fun duplicate(id: String, name: String): EqProfile {
        val original = requireNotNull(find(id)) { "No profile $id" }
        val stamp = now()
        val category = when (original.category) {
            ProfileCategory.MEASURED, ProfileCategory.DEVICE_PRESET -> original.category
            else -> ProfileCategory.USER
        }
        return add(original.copy(id = newId(), name = name.trim(), category = category, createdAt = stamp, updatedAt = stamp))
    }

    fun rename(id: String, name: String): EqProfile = update(id) { it.copy(name = name.trim()) }

    fun updateSteps(id: String, steps: List<Int>, clearBass: Int? = null): EqProfile = update(id) {
        it.copy(
            deviceEq = DeviceEqSetting(steps = steps, clearBass = clearBass),
            category = if (it.category == ProfileCategory.MEASURED) ProfileCategory.USER else it.category,
        )
    }

    fun delete(id: String) {
        require(!id.startsWith(EqProfile.BUILTIN_PREFIX)) { "Built-in profiles cannot be deleted" }
        userProfiles = userProfiles.filterNot { it.id == id }
        store.save(userProfiles)
    }

    /** Imports an export file. Every imported profile gets a fresh id so nothing is overwritten. */
    fun importText(text: String): List<EqProfile> {
        val stamp = now()
        val imported = ProfileCodec.decode(text).map { p ->
            p.copy(id = newId(), category = if (p.category == ProfileCategory.DEVICE_PRESET && p.deviceEq.steps != null) ProfileCategory.USER else p.category, updatedAt = stamp)
        }
        userProfiles = userProfiles + imported
        store.save(userProfiles)
        return imported
    }

    fun exportText(ids: Collection<String>? = null): String {
        val selected = if (ids == null) userProfiles else (BuiltinProfiles.all + userProfiles).filter { it.id in ids }
        return ProfileCodec.encode(selected, now())
    }

    companion object {
        const val SOUND_CONNECT_NOTE = "Importado de un ajuste de usuario de Sound Connect (preset 0x%02x)."
    }

    private fun add(profile: EqProfile): EqProfile {
        ProfileCodec.validate(profile)?.let { throw ProfileFormatException(it) }
        userProfiles = userProfiles + profile
        store.save(userProfiles)
        return profile
    }

    private fun update(id: String, change: (EqProfile) -> EqProfile): EqProfile {
        require(!id.startsWith(EqProfile.BUILTIN_PREFIX)) { "Built-in profiles cannot be edited; duplicate them first" }
        val current = requireNotNull(userProfiles.firstOrNull { it.id == id }) { "No profile $id" }
        val updated = change(current).copy(updatedAt = now())
        ProfileCodec.validate(updated)?.let { throw ProfileFormatException(it) }
        userProfiles = userProfiles.map { if (it.id == id) updated else it }
        store.save(userProfiles)
        return updated
    }
}
