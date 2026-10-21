package io.github.andru1989.xmaudiostudio.core.presets

import io.github.andru1989.xmaudiostudio.core.device.DeviceCatalog
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.eq.FilterType
import io.github.andru1989.xmaudiostudio.core.eq.ParametricFilter
import io.github.andru1989.xmaudiostudio.core.eq.ParametricProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Profile model of shared/preset-schema/profiles.schema.json (same file the Swift app reads/writes). */
@Serializable
enum class ProfileCategory {
    /** A Sony built-in preset selected by code. */
    @SerialName("device-preset") DEVICE_PRESET,
    /** Derived from an acoustic measurement (e.g. an imported AutoEq profile). */
    @SerialName("measured") MEASURED,
    /** Designed by taste; never presented as a correction. */
    @SerialName("subjective") SUBJECTIVE,
    @SerialName("user") USER,
}

/** Exactly one of [presetCode] (select a Sony preset) or [steps] (custom curve). */
@Serializable
data class DeviceEqSetting(
    val presetCode: Int? = null,
    val steps: List<Int>? = null,
    val clearBass: Int? = null,
)

@Serializable
enum class CurveFilterType(val filterType: FilterType) {
    @SerialName("peaking") PEAKING(FilterType.PEAKING),
    @SerialName("lowShelf") LOW_SHELF(FilterType.LOW_SHELF),
    @SerialName("highShelf") HIGH_SHELF(FilterType.HIGH_SHELF);

    companion object {
        fun of(type: FilterType) = entries.first { it.filterType == type }
    }
}

@Serializable
data class CurveFilter(val type: CurveFilterType, val frequency: Double, val gainDb: Double, val q: Double)

@Serializable
data class SourceCurve(val preampDb: Double, val filters: List<CurveFilter>) {
    fun toParametric() = ParametricProfile(preampDb, filters.map { ParametricFilter(it.type.filterType, it.frequency, it.gainDb, it.q) })

    companion object {
        fun of(p: ParametricProfile) = SourceCurve(p.preampDb, p.filters.map { CurveFilter(CurveFilterType.of(it.type), it.frequency, it.gainDb, it.q) })
    }
}

@Serializable
data class ProfileSource(val description: String? = null, val url: String? = null, val attribution: String? = null)

@Serializable
data class EqProfile(
    val id: String,
    val name: String,
    val category: ProfileCategory,
    /** [HeadphoneModel.id]; a profile is only offered for, and applied to, its own model. */
    val model: String,
    val deviceEq: DeviceEqSetting,
    val sourceCurve: SourceCurve? = null,
    val source: ProfileSource? = null,
    val notes: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    val isBuiltin: Boolean get() = id.startsWith(BUILTIN_PREFIX)

    companion object {
        const val BUILTIN_PREFIX = "builtin."
    }
}

@Serializable
data class ProfileExport(
    val format: String,
    val version: Int,
    val exportedAt: String? = null,
    val profiles: List<EqProfile>,
)

class ProfileFormatException(message: String) : Exception(message)

object ProfileCodec {
    const val FORMAT = "xm-audio-studio.profiles"
    const val VERSION = 1
    const val MAX_NAME_LENGTH = 80

    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(profiles: List<EqProfile>, exportedAt: String? = null): String =
        json.encodeToString(ProfileExport.serializer(), ProfileExport(FORMAT, VERSION, exportedAt, profiles))

    /** Parses and validates an export file; throws [ProfileFormatException] with a readable reason. */
    fun decode(text: String): List<EqProfile> {
        val doc = try {
            json.decodeFromString(ProfileExport.serializer(), text)
        } catch (e: SerializationException) {
            throw ProfileFormatException("Not a valid profile file: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: IllegalArgumentException) {
            throw ProfileFormatException("Not a valid profile file: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        if (doc.format != FORMAT) throw ProfileFormatException("Unknown format '${doc.format}'")
        if (doc.version > VERSION) throw ProfileFormatException("Profile file version ${doc.version} is newer than this app supports ($VERSION)")
        doc.profiles.forEach { p -> validate(p)?.let { throw ProfileFormatException("${p.name.ifBlank { p.id }}: $it") } }
        return doc.profiles
    }

    /** Null when valid, otherwise the reason. */
    fun validate(p: EqProfile): String? {
        if (p.id.isBlank()) return "missing id"
        if (p.name.isBlank() || p.name.length > MAX_NAME_LENGTH) return "name must be 1-$MAX_NAME_LENGTH characters"
        val model = HeadphoneModel.fromId(p.model) ?: return "unknown model '${p.model}'"
        val spec = DeviceCatalog.capabilities(model).equalizer ?: return "${model.displayName} has no equalizer"
        val eq = p.deviceEq
        if ((eq.presetCode == null) == (eq.steps == null)) return "needs exactly one of presetCode or steps"
        eq.presetCode?.let { code ->
            if (spec.presets.none { it.code == code }) return "preset 0x%02x is not available on %s".format(code, model.displayName)
        }
        eq.steps?.let { steps ->
            if (steps.size != spec.bandCount) return "expected ${spec.bandCount} bands, found ${steps.size}"
            if (steps.any { it < spec.minStep || it > spec.maxStep }) return "band values must be within ${spec.minStep}..${spec.maxStep}"
        }
        eq.clearBass?.let { cb ->
            val range = spec.clearBassRange ?: return "${model.displayName} has no Clear Bass"
            if (cb !in range) return "Clear Bass must be within $range"
        }
        return null
    }
}
