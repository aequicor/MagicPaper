package io.aequicor.magicpaper.domain

fun testModelDossiers(values: List<ModelDossier> = emptyList()): ModelDossierRepository = object : ModelDossierRepository {
    private val saved = values.toList()
    override suspend fun dossiers() = saved
}
