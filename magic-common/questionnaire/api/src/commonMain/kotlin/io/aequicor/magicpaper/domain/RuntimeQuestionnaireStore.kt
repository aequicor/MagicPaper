package io.aequicor.magicpaper.domain

/** Legacy snapshot source. The journal owner imports it once and empties it during application reset. */
interface RuntimeQuestionnaireStore {
    fun load(): List<RuntimeQuestionnaireRecord>
    fun save(records: List<RuntimeQuestionnaireRecord>)
}
