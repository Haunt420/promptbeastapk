package promptbeast

/** How the image model behind a generator reads prompts. Data, not code: add entries instead of changing the engine. */
data class ModelProfile(
    val id: String,
    val name: String,
    val prose: Boolean,          // reads connected sentences better than tag lists
    val readsWeights: Boolean,   // understands (word:1.2) / ((word))
    val negative: String,        // "none" | "weak" | "full"
    val note: String = "",
)

object Profiles {
    const val DEFAULT = "chroma"
    val ALL: Map<String, ModelProfile> = linkedMapOf(
        "chroma" to ModelProfile("chroma-2026-09", "Chroma (prose)", prose = true, readsWeights = false, negative = "weak",
            note = "Flux.1-schnell-derived, uncensored. Per Mack's source, 2026-09."),
        "flux_schnell" to ModelProfile("flux-schnell", "Flux Schnell (prose)", prose = true, readsWeights = false, negative = "none",
            note = "Per The Imaginarium's help text; negatives and brackets ignored."),
        "sdxl_tags" to ModelProfile("sdxl-tags", "SDXL / Pony (tags)", prose = false, readsWeights = true, negative = "full",
            note = "Classic tag + weight checkpoints."),
    )

    fun get(key: String): ModelProfile = ALL[key] ?: ALL.getValue(DEFAULT)
}
