package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.SettingsMachine.Fact
import io.aequicor.magicpaper.domain.SettingsMachine.Intent
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The representatives of [SettingsSpace], kept here rather than in the api so a shipped binary carries
 * no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce. Every one is loaded at settings
 * version `v1` with the one profile `provider` at version `v1`, so a single reference and expectation
 * per input fit every position that accepts it. The change in flight is always `change`, attempt `change`.
 */
class SettingsSpaceTest {
    private fun profile(id: String) = SettingsProfileRecord(LlmProfile(id, id, baseUrl = "https://provider.test", modelId = "model"))
    private val record = SettingsRecord(AppSettings())
    private val ref = SettingsProfileRef("provider", "v1")
    private val dossier = ModelDossier("dossier", "provider", "model")
    private val catalogRequest = SettingsCatalogRequest("catalog", ref, SettingsCatalogKind.MODELS)
    private val descriptionRequest = SettingsDescriptionRequest("description", ref, ref, "model", "v1", null)
    private fun step(state: SettingsMachine.State, input: SettingsMachine.Input) = SettingsMachine.reduce(state, input).state

    private val new = SettingsMachine.initial()
    private val idleNoProfile = step(new, Fact.Initialized(record, emptyList(), emptyList(), "v1"))
    private val idle = step(new, Fact.Initialized(record, listOf(profile("provider")), emptyList(), "v1"))
    private val preparing = step(idle, Intent.ChangeSettings("v1", record, "change"))
    private val applying = step(preparing, Fact.RuntimePrepared("change", "change"))
    private val loadingCatalog = step(idle, Intent.BeginCatalog(catalogRequest))
    private val describing = step(idle, Intent.BeginDescription(descriptionRequest))
    private val preparingUnknown = step(preparing, Fact.RuntimeUnknown("change", "change"))
    private val applyingUnknown = step(applying, Fact.RuntimeUnknown("change", "change"))
    private val persistenceUnknown = step(idle, Fact.PersistenceUnknown)

    /**
     * The harness drives one thing at a time, so it cannot see how [SettingsSpace.label] ranks a state
     * that holds several. Reversing that order leaves it green, and the order is what says a change in
     * flight outranks a catalog, and a catalog outranks a description.
     */
    @Test fun theMostPressingThingInFlightNamesTheStatePosition() {
        val both = step(loadingCatalog, Intent.BeginDescription(descriptionRequest))
        assertEquals(SettingsSpace.LOADING_CATALOG, SettingsSpace.label(both), "a catalog outranks a description")
        val changing = step(both, Intent.ChangeSettings("v1", record, "change"))
        assertEquals(SettingsSpace.CHANGE_PREPARING, SettingsSpace.label(changing), "a change outranks both")
        // A profile deleted while its catalog is loading leaves the position at the catalog, which still
        // accepts its completion.
        val orphaned = step(loadingCatalog, Intent.DeleteProfile(ref, "v2"))
        assertEquals(SettingsSpace.LOADING_CATALOG, SettingsSpace.label(orphaned))
        // A request interrupted by a restart is labelled as still loading; its result is discarded, not applied.
        assertEquals(SettingsSpace.LOADING_CATALOG, SettingsSpace.label(step(loadingCatalog, Fact.Interrupted)))
        assertEquals(SettingsSpace.CHANGE_APPLYING_UNKNOWN, SettingsSpace.label(step(applying, Fact.Interrupted)))
    }

    /** Position and predicate agree exactly at the positions that hold an unresolved outcome. */
    @Test fun unknownIsExactlyAnUnresolvedChangeOrUnconfirmedPersistence() {
        val unknownPositions = setOf(SettingsSpace.CHANGE_PREPARING_UNKNOWN, SettingsSpace.CHANGE_APPLYING_UNKNOWN, SettingsSpace.PERSISTENCE_UNKNOWN)
        val states = mapOf(
            SettingsSpace.NEW to new, SettingsSpace.IDLE_NO_PROFILE to idleNoProfile, SettingsSpace.IDLE to idle,
            SettingsSpace.LOADING_CATALOG to loadingCatalog, SettingsSpace.DESCRIBING to describing,
            SettingsSpace.CHANGE_PREPARING to preparing, SettingsSpace.CHANGE_APPLYING to applying,
            SettingsSpace.CHANGE_PREPARING_UNKNOWN to preparingUnknown, SettingsSpace.CHANGE_APPLYING_UNKNOWN to applyingUnknown,
            SettingsSpace.PERSISTENCE_UNKNOWN to persistenceUnknown,
        )
        for ((position, state) in states) assertEquals(position in unknownPositions, SettingsSpace.unknown(state), position.name)
    }

    /**
     * Unconfirmed persistence fences before anything else, a store that was never loaded included. The
     * representatives reach it only from a loaded store, so they cannot tell the two orders apart.
     */
    @Test fun unconfirmedPersistenceOutranksAStoreThatWasNeverLoaded() {
        assertEquals(SettingsSpace.PERSISTENCE_UNKNOWN, SettingsSpace.label(step(new, Fact.PersistenceUnknown)))
    }

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        SettingsMachine,
        states = mapOf(
            SettingsSpace.NEW to new,
            SettingsSpace.IDLE_NO_PROFILE to idleNoProfile,
            SettingsSpace.IDLE to idle,
            SettingsSpace.LOADING_CATALOG to loadingCatalog,
            SettingsSpace.DESCRIBING to describing,
            SettingsSpace.CHANGE_PREPARING to preparing,
            SettingsSpace.CHANGE_APPLYING to applying,
            // The runtime may or may not have taken the new settings: the change must not be replayed blind.
            SettingsSpace.CHANGE_PREPARING_UNKNOWN to preparingUnknown,
            SettingsSpace.CHANGE_APPLYING_UNKNOWN to applyingUnknown,
            SettingsSpace.PERSISTENCE_UNKNOWN to persistenceUnknown,
        ),
        inputs = mapOf(
            SettingsSpace.CHANGE_SETTINGS to Intent.ChangeSettings("v1", record, "new-change"),
            SettingsSpace.RETRY_SETTINGS_CHANGE to Intent.RetrySettingsChange("change", "retry"),
            SettingsSpace.SAVE_SETTINGS to Intent.SaveSettings("v1", record, "v2"),
            SettingsSpace.SAVE_PROFILE_NEW to Intent.SaveProfile(null, profile("other"), "v2"),
            SettingsSpace.SAVE_PROFILE_UPDATE to Intent.SaveProfile(ref, profile("provider"), "v2"),
            SettingsSpace.DELETE_PROFILE to Intent.DeleteProfile(ref, "v2"),
            SettingsSpace.SAVE_DOSSIER to Intent.SaveDossier(ref, dossier, null, "v2"),
            SettingsSpace.BEGIN_CATALOG to Intent.BeginCatalog(SettingsCatalogRequest("catalog-new", ref, SettingsCatalogKind.MODELS)),
            SettingsSpace.BEGIN_DESCRIPTION to Intent.BeginDescription(SettingsDescriptionRequest("description-new", ref, ref, "model", "v1", null)),
            SettingsSpace.IMPORT to Intent.Import(record, emptyList(), emptyList(), "v2"),
            SettingsSpace.CLEAR to Intent.Clear("v2"),
            SettingsSpace.RUNTIME_PREPARED to Fact.RuntimePrepared("change", "change"),
            SettingsSpace.RUNTIME_APPLIED to Fact.RuntimeApplied("change", "change"),
            SettingsSpace.RUNTIME_UNKNOWN to Fact.RuntimeUnknown("change", "change"),
            SettingsSpace.INITIALIZED to Fact.Initialized(record, listOf(profile("provider")), emptyList(), "v1"),
            SettingsSpace.CATALOG_LOADED to Fact.CatalogLoaded(catalogRequest, emptyList(), "v2"),
            SettingsSpace.DESCRIPTION_LOADED to Fact.DescriptionLoaded(descriptionRequest, dossier, "v2"),
            SettingsSpace.OPERATION_FAILED to Fact.OperationFailed("catalog"),
            SettingsSpace.NEIGHBOUR_MISSING to Fact.NeighbourMissing("catalog", "provider"),
            SettingsSpace.INTERRUPTED to Fact.Interrupted,
            SettingsSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
        ),
    )
}
