package io.github.andru1989.xmaudiostudio.core.presets

/**
 * Profiles shipped with the app. Must equal shared/preset-schema/builtin-profiles.json
 * (checked by ProfileConformanceTest and the Swift suite). Subjective curves are labelled
 * as such; there is no measured correction here because AutoEq data carries no licence.
 */
object BuiltinProfiles {
    private const val SUBJECTIVE = "Perfil subjetivo diseñado por gusto, no una corrección medida."
    private const val XM6 = "wh-1000xm6"

    private fun preset(id: String, name: String, code: Int, notes: String? = null) =
        EqProfile("builtin.$id", name, ProfileCategory.DEVICE_PRESET, XM6, DeviceEqSetting(presetCode = code), notes = notes)

    private fun curve(id: String, name: String, steps: List<Int>, notes: String = SUBJECTIVE) =
        EqProfile("builtin.$id", name, ProfileCategory.SUBJECTIVE, XM6, DeviceEqSetting(steps = steps), notes = notes)

    val all: List<EqProfile> = listOf(
        preset("sony-original", "Original Sony", 0x00, "Desactiva el EQ de los audífonos: afinación de fábrica de Sony."),
        curve("flat", "Plano", List(10) { 0 }, "Curva personalizada a cero. Equivale al sonido de fábrica, no a una respuesta neutra medida."),
        curve("controlled-bass", "Graves controlados", listOf(-3, -2, -1, 0, 0, 0, 0, 0, 0, 0)),
        curve("clear-vocals", "Voces claras", listOf(-2, -1, -1, 0, 1, 2, 3, 2, 0, 0)),
        curve("electronic", "Música electrónica", listOf(4, 3, 1, 0, -1, -1, 0, 1, 2, 2)),
        curve("rock", "Rock", listOf(3, 2, 0, -1, -1, 0, 1, 2, 2, 1)),
        curve("classical", "Música clásica", listOf(1, 1, 0, 0, -1, -1, 0, 1, 2, 1)),
        curve("movies", "Películas", listOf(3, 2, 1, 0, 0, 1, 2, 1, 0, 0)),
        curve("gaming", "Videojuegos", listOf(1, 1, 0, -1, 0, 1, 3, 3, 1, 0)),
        curve("podcast", "Podcast", listOf(-4, -3, -2, 0, 1, 2, 2, 1, -1, -2)),
        preset("sony-heavy", "Sony Heavy", 0x30),
        preset("sony-clear", "Sony Clear", 0x31),
        preset("sony-hard", "Sony Hard", 0x32),
        preset("sony-soft", "Sony Soft", 0x33),
    )

    fun forModel(modelId: String) = all.filter { it.model == modelId }
}
