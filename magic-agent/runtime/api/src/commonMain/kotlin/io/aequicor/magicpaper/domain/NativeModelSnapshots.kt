package io.aequicor.magicpaper.domain

/**
 * Последний известный снимок каталога движка для планирования и допуска этапов. Опроса движка
 * здесь нет: снимок держит [CodingModelCatalog], а этот порт лишь отдаёт его владельцам плана.
 * `null` — у движка нет нативного каталога, снимка ещё нет или он не должен применяться.
 */
fun interface NativeModelSnapshots {
    suspend fun snapshot(engine: CodingEngine?): CodingModelSnapshot?

    companion object {
        val None = NativeModelSnapshots { null }
    }
}
