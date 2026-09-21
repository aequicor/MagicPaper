package io.aequicor.magicpaper.domain

/** Model descriptions are available on every host and do not own native plans. */
interface ModelDossierRepository {
    suspend fun dossiers(): List<ModelDossier>
}
