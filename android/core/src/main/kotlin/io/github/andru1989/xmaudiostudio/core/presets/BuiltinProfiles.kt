package io.github.andru1989.xmaudiostudio.core.presets

/**
 * Profiles shipped with the app. Must equal shared/preset-schema/builtin-profiles.json
 * (checked by ProfileConformanceTest and the Swift suite). Our own curves are subjective;
 * the community ones are EQ settings their authors published, credited in [EqProfile.source]
 * and the notes (docs/research/community-eq-presets.md). No AutoEq data: it carries no licence.
 */
object BuiltinProfiles {
    private const val XM6 = "wh-1000xm6"

    private fun preset(id: String, name: String, code: Int, notes: String? = null) =
        EqProfile("builtin.$id", name, ProfileCategory.DEVICE_PRESET, XM6, DeviceEqSetting(presetCode = code), notes = notes)

    private fun curve(id: String, name: String, steps: List<Int>, notes: String? = null) =
        EqProfile("builtin.$id", name, ProfileCategory.SUBJECTIVE, XM6, DeviceEqSetting(steps = steps), notes = notes)

    private fun community(id: String, name: String, category: ProfileCategory, steps: List<Int>, notes: String, url: String, attribution: String) =
        EqProfile(
            "builtin.$id", name, category, XM6, DeviceEqSetting(steps = steps),
            source = ProfileSource(url = url, attribution = attribution), notes = notes,
        )

    val all: List<EqProfile> = listOf(
        preset("sony-original", "Original Sony", 0x00, "Desactiva el EQ de los audífonos: afinación de fábrica de Sony."),
        curve("flat", "Plano", List(10) { 0 }, "Curva personalizada a cero. Equivale al sonido de fábrica, no a una respuesta neutra medida."),
        curve("controlled-bass", "Graves controlados", listOf(-3, -2, -1, 0, 0, 0, 0, 0, 0, 0)),
        curve("clear-vocals", "Voces claras", listOf(-2, -1, -1, 0, 1, 2, 3, 2, 0, 0)),
        curve("electronic", "Música electrónica", listOf(4, 3, 1, 0, -1, -1, 0, 1, 2, 2)),
        curve("rock", "Rock", listOf(3, 2, 0, -1, -1, 0, 1, 2, 2, 1)),
        curve("hip-hop", "Rap / Hip hop", listOf(3, 4, 0, -2, -2, 0, 2, 2, 1, 0)),
        curve("classical", "Música clásica", listOf(1, 1, 0, 0, -1, -1, 0, 1, 2, 1)),
        curve("movies", "Películas", listOf(3, 2, 1, 0, 0, 1, 2, 1, 0, 0)),
        curve("gaming", "Videojuegos", listOf(1, 1, 0, -1, 0, 1, 3, 3, 1, 0)),
        curve("podcast", "Podcast", listOf(-4, -3, -2, 0, 1, 2, 2, 1, -1, -2)),
        community(
            "community-v-bright", "V brillante · Loud and Wireless", ProfileCategory.SUBJECTIVE, listOf(2, 3, 1, -1, 1, 1, 0, 0, 4, 5),
            "Comunidad: «Singapore Spring» de Loud and Wireless (2025), muy bien valorado. Más sub-bajo, aire y brillo.",
            "https://www.youtube.com/watch?v=3UhCS97tlUA", "Aaron, Loud and Wireless",
        ),
        community(
            "community-super-review", "Claridad · Super* Review", ProfileCategory.MEASURED, listOf(1, -3, 0, 0, 4, 2, 2, 3, 1, 5),
            "Comunidad: ajuste del vídeo de Super* Review (2025), por medición y preferencia. Menos bajo medio, medios adelante.",
            "https://www.youtube.com/watch?v=vXe5OzSrpp8", "Super* Review",
        ),
        community(
            "community-super-review-neutral", "Neutro · Super* Review", ProfileCategory.MEASURED, listOf(1, -1, -4, 1, 2, 5, -2, 5, 3, 1),
            "Comunidad: receta neutra de Super* Review (2025), guiada por medición.",
            "https://www.youtube.com/watch?v=vXe5OzSrpp8", "Super* Review (comentario fijado)",
        ),
        community(
            "community-harman-hc", "Harman · Headphone Corner", ProfileCategory.MEASURED, listOf(2, 1, -1, 1, 1, 5, -1, 3, 3, -2),
            "Comunidad: medición de Headphone Corner (2025) hacia la curva Harman 2018, con cancelación de ruido activa.",
            "https://kuulokenurkka.com/en/review-sony-wh-1000xm6/", "Sword of Damocles, Headphone Corner (Kuulokenurkka)",
        ),
        preset("sony-heavy", "Sony Heavy", 0x30),
        preset("sony-clear", "Sony Clear", 0x31),
        preset("sony-hard", "Sony Hard", 0x32),
        preset("sony-soft", "Sony Soft", 0x33),
    )

    fun forModel(modelId: String) = all.filter { it.model == modelId }
}
