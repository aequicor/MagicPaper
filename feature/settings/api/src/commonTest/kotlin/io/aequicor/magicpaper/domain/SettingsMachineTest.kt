package io.aequicor.magicpaper.domain

import kotlin.test.*

class SettingsMachineTest {
    private val profile = SettingsProfileRecord(LlmProfile("provider", "Provider", baseUrl = "https://provider.test", modelId = "model"))
    private fun initialized() = step(SettingsMachine.initial(), SettingsMachine.Fact.Initialized(
        SettingsRecord(AppSettings()), listOf(profile), emptyList(), "initial"))
    private fun step(state: SettingsMachine.State, input: SettingsMachine.Input): SettingsMachine.State {
        val transition = SettingsMachine.reduce(state, input)
        assertTrue(transition.effects.none { it is SettingsMachine.Effect.Reject }, transition.effects.toString())
        return transition.state
    }

    @Test fun lateCatalogDoesNotOverwriteEditedProfile() {
        val initial = initialized()
        val request = SettingsCatalogRequest("request", initial.profileRef("provider")!!, SettingsCatalogKind.MODELS)
        val loading = step(initial, SettingsMachine.Intent.BeginCatalog(request))
        val edited = step(loading, SettingsMachine.Intent.SaveProfile(request.profile,
            profile.copy(value = profile.value.copy(name = "Edited")), "edit"))
        val completion = SettingsMachine.reduce(edited, SettingsMachine.Fact.CatalogLoaded(request,
            listOf(ProviderModel("late")), "response"))
        assertEquals("Edited", completion.state.profiles.getValue("provider").record.value.name)
        assertTrue(completion.state.profiles.getValue("provider").record.value.modelCatalog.isEmpty())
        assertEquals(listOf(SettingsMachine.Effect.ResultDiscarded("request")), completion.effects)
        assertTrue(completion.state.catalogs.isEmpty())
    }

    @Test fun deleteAndRecreateSameIdDoesNotAcceptOldCatalog() {
        val initial = initialized()
        val request = SettingsCatalogRequest("request", initial.profileRef("provider")!!, SettingsCatalogKind.MODELS)
        val loading = step(initial, SettingsMachine.Intent.BeginCatalog(request))
        val removed = step(loading, SettingsMachine.Intent.DeleteProfile(request.profile, "delete"))
        val recreated = step(removed, SettingsMachine.Intent.SaveProfile(null, profile, "recreate"))
        val completed = step(recreated, SettingsMachine.Fact.CatalogLoaded(request, listOf(ProviderModel("late")), "response"))
        assertEquals("recreate", completed.profileRef("provider")!!.version)
        assertTrue(completed.profiles.getValue("provider").record.value.modelCatalog.isEmpty())
        assertEquals("delete", completed.profileTombstones["provider"])
    }

    @Test fun descriptionCannotReplaceManualEditOrRecreatedProfile() {
        val initial = initialized()
        val ref = initial.profileRef("provider")!!
        val request = SettingsDescriptionRequest("description", ref, ref, "model", initial.settingsVersion, null)
        val researching = step(initial, SettingsMachine.Intent.BeginDescription(request))
        val manual = ModelDossier("manual", "provider", "model", strengths = "My description")
        val edited = step(researching, SettingsMachine.Intent.SaveDossier(ref, manual, null, "manual-save"))
        val late = ModelDossier("generated", "provider", "model", strengths = "Late research", source = DossierSource.WEB)
        val completed = step(edited, SettingsMachine.Fact.DescriptionLoaded(request, late, "response"))
        assertEquals(manual, completed.dossiers.getValue(SettingsDossierKey("provider", "model")).dossier)
        assertTrue(completed.descriptions.isEmpty())
    }

    @Test fun deletingSelectedProfileClearsDefaultInSameTransition() {
        val base = initialized()
        val selected = step(base, SettingsMachine.Intent.SaveSettings(base.settingsVersion,
            SettingsRecord(base.settings.value.copy(activeLlmProfileId = "provider", defaultModel = ModelSelection("provider", "model"))), "select"))
        val removed = step(selected, SettingsMachine.Intent.DeleteProfile(selected.profileRef("provider")!!, "delete"))
        assertTrue(removed.profiles.isEmpty())
        assertEquals("", removed.settings.value.activeLlmProfileId)
        assertNull(removed.settings.value.defaultModel)
        assertEquals("delete", removed.settingsVersion)
    }

    @Test fun foreignCompletionIsRejectedWithoutRemovingActualRequest() {
        val initial = initialized()
        val request = SettingsCatalogRequest("request", initial.profileRef("provider")!!, SettingsCatalogKind.MODELS)
        val loading = step(initial, SettingsMachine.Intent.BeginCatalog(request))
        val actual = SettingsMachine.reduce(loading, SettingsMachine.Fact.CatalogLoaded(
            request.copy(profile = request.profile.copy(version = "foreign")), emptyList(), "response"))
        assertEquals(loading, actual.state)
        assertIs<SettingsMachine.Effect.Reject>(actual.effects.single())
    }

    @Test fun restoreMarksInFlightOperationsUnknownWithoutEffects() {
        val initial = initialized()
        val request = SettingsCatalogRequest("request", initial.profileRef("provider")!!, SettingsCatalogKind.MODELS)
        val loading = step(initial, SettingsMachine.Intent.BeginCatalog(request))
        val restore = SettingsMachine.reduce(loading, SettingsMachine.Fact.Interrupted)
        assertTrue(restore.effects.isEmpty())
        assertEquals(setOf("request"), restore.state.interrupted)
        val late = SettingsMachine.reduce(restore.state, SettingsMachine.Fact.CatalogLoaded(request, listOf(ProviderModel("late")), "response"))
        assertIs<SettingsMachine.Effect.ResultDiscarded>(late.effects.single())
        assertEquals(initial.profiles, late.state.profiles)
    }

    @Test fun persistenceUnknownDominatesEveryMutationAndCompletion() {
        val state = step(initialized(), SettingsMachine.Fact.PersistenceUnknown)
        val ref = state.profileRef("provider")!!
        val request = SettingsCatalogRequest("request", ref, SettingsCatalogKind.MODELS)
        val inputs = listOf(SettingsMachine.Intent.SaveSettings(state.settingsVersion, state.settings, "save"),
            SettingsMachine.Intent.SaveProfile(ref, profile, "save"), SettingsMachine.Intent.DeleteProfile(ref, "delete"),
            SettingsMachine.Intent.Clear("clear"), SettingsMachine.Intent.BeginCatalog(request),
            SettingsMachine.Fact.CatalogLoaded(request, emptyList(), "response"), SettingsMachine.Fact.Interrupted)
        inputs.forEach { input ->
            val result = SettingsMachine.reduce(state, input)
            assertEquals(state, result.state)
            assertIs<SettingsMachine.Effect.Reject>(result.effects.single())
        }
    }

    @Test fun plaintextCredentialsAreRejectedBeforeTheyCanEnterTheJournal() {
        val initial = initialized()
        val inputs = listOf(SettingsMachine.Intent.SaveSettings(initial.settingsVersion,
            SettingsRecord(AppSettings(googleApiKey = "secret")), "save"),
            SettingsMachine.Intent.SaveProfile(initial.profileRef("provider"),
                profile.copy(value = profile.value.copy(apiKey = "secret")), "save"))
        inputs.forEach { input ->
            val result = SettingsMachine.reduce(initial, input)
            assertEquals(initial, result.state)
            assertIs<SettingsMachine.Effect.Reject>(result.effects.single())
        }
    }
}
