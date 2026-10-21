package io.github.andru1989.xmaudiostudio.core.presets

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

    /** Saves a custom curve as a new user profile. */
    fun saveCurve(
        name: String,
        modelId: String,
        steps: List<Int>,
        category: ProfileCategory = ProfileCategory.USER,
        sourceCurve: SourceCurve? = null,
        source: ProfileSource? = null,
        notes: String? = null,
    ): EqProfile {
        val stamp = now()
        val profile = EqProfile(newId(), name.trim(), category, modelId, DeviceEqSetting(steps = steps), sourceCurve, source, notes, stamp, stamp)
        return add(profile)
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

    fun updateSteps(id: String, steps: List<Int>): EqProfile =
        update(id) { it.copy(deviceEq = DeviceEqSetting(steps = steps), category = if (it.category == ProfileCategory.MEASURED) ProfileCategory.USER else it.category) }

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
