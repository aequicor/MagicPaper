package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [SettingsMachine], declared so it can be read without running anything.
 *
 * The state has no phase, and most of what it refuses is optimistic concurrency — every intent carries
 * the settings version or the profile reference it saw — which is identity, not position. The
 * positions are what the reducer gates on before it looks at an identity, in the order it does:
 *
 *  - persistence unknown fences everything but its own fact, then an uninitialized store refuses
 *    everything but the load that initializes it;
 *  - a settings change in flight (`change`) refuses every intent except a retry. It is four positions,
 *    because each fact of the protocol is accepted only in its own phase and only while the outcome is
 *    not unknown, and a retry only when it is: preparing or applying, each known or unknown. This is
 *    the one outcome the machine can leave unresolved, which is why it is what `unknown(state)` reports;
 *  - otherwise a catalog load in flight and a description in flight are positions of their own, since
 *    each is the only place its completion fact is accepted, and a profile already loading a catalog
 *    refuses another. An idle store is split by whether it holds a profile, which every profile
 *    intent needs.
 *
 * What the declaration cannot express, and leaves to `SettingsMachineTest`: a stale settings version, a
 * profile reference that no longer matches, a request whose identity is not the one in flight, a
 * change whose request or attempt id is not the current one, a blank version, invalid settings or
 * profiles, and the policy checks (`Import` and `Clear` require the runtime policy to be already in
 * place, `SaveSettings` refuses to change it). The representatives all live at version `v1` with the one
 * profile `provider` at version `v1`, so the references and expectations below fit every position.
 *
 * Where the position is coarser than the state: a request interrupted by a restart is labelled as
 * still loading, because it accepts and refuses exactly what a live one does — its result is discarded
 * instead of applied, which is an effect, not a position. A change in flight outranks a catalog or a
 * description; a catalog outranks a description; and a profile deleted while a catalog or a description
 * is in flight leaves the position at that request, which still accepts its completion. `missingNeighbour`
 * is what the interface shows and is not read by any transition.
 */
object SettingsSpace : StateSpace<SettingsMachine.State, SettingsMachine.Input, SettingsMachine.Effect> {
    val NEW = PhaseId("new")
    val IDLE_NO_PROFILE = PhaseId("idle-no-profile")
    val IDLE = PhaseId("idle")
    val LOADING_CATALOG = PhaseId("loading-catalog")
    val DESCRIBING = PhaseId("describing")
    val CHANGE_PREPARING = PhaseId("change-preparing")
    val CHANGE_APPLYING = PhaseId("change-applying")
    val CHANGE_PREPARING_UNKNOWN = PhaseId("change-preparing-unknown")
    val CHANGE_APPLYING_UNKNOWN = PhaseId("change-applying-unknown")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")

    val CHANGE_SETTINGS = InputId("ChangeSettings")
    val RETRY_SETTINGS_CHANGE = InputId("RetrySettingsChange")
    val SAVE_SETTINGS = InputId("SaveSettings")
    val SAVE_PROFILE_NEW = InputId("SaveProfileNew")
    val SAVE_PROFILE_UPDATE = InputId("SaveProfileUpdate")
    val DELETE_PROFILE = InputId("DeleteProfile")
    val SAVE_DOSSIER = InputId("SaveDossier")
    val BEGIN_CATALOG = InputId("BeginCatalog")
    val BEGIN_DESCRIPTION = InputId("BeginDescription")
    val IMPORT = InputId("Import")
    val CLEAR = InputId("Clear")
    val RUNTIME_PREPARED = InputId("RuntimePrepared")
    val RUNTIME_APPLIED = InputId("RuntimeApplied")
    val RUNTIME_UNKNOWN = InputId("RuntimeUnknown")
    val INITIALIZED = InputId("Initialized")
    val CATALOG_LOADED = InputId("CatalogLoaded")
    val DESCRIPTION_LOADED = InputId("DescriptionLoaded")
    val OPERATION_FAILED = InputId("OperationFailed")
    val NEIGHBOUR_MISSING = InputId("NeighbourMissing")
    val INTERRUPTED = InputId("Interrupted")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(
        NEW, IDLE_NO_PROFILE, IDLE, LOADING_CATALOG, DESCRIBING,
        CHANGE_PREPARING, CHANGE_APPLYING, CHANGE_PREPARING_UNKNOWN, CHANGE_APPLYING_UNKNOWN, PERSISTENCE_UNKNOWN,
    )

    override val inputs = listOf(
        InputSpec(CHANGE_SETTINGS, Branch.INTENT),
        InputSpec(RETRY_SETTINGS_CHANGE, Branch.INTENT),
        InputSpec(SAVE_SETTINGS, Branch.INTENT),
        InputSpec(SAVE_PROFILE_NEW, Branch.INTENT),
        InputSpec(SAVE_PROFILE_UPDATE, Branch.INTENT),
        InputSpec(DELETE_PROFILE, Branch.INTENT),
        InputSpec(SAVE_DOSSIER, Branch.INTENT),
        InputSpec(BEGIN_CATALOG, Branch.INTENT),
        InputSpec(BEGIN_DESCRIPTION, Branch.INTENT),
        InputSpec(IMPORT, Branch.INTENT),
        InputSpec(CLEAR, Branch.INTENT),
        InputSpec(RUNTIME_PREPARED, Branch.FACT),
        InputSpec(RUNTIME_APPLIED, Branch.FACT),
        InputSpec(RUNTIME_UNKNOWN, Branch.FACT),
        InputSpec(INITIALIZED, Branch.FACT),
        InputSpec(CATALOG_LOADED, Branch.FACT),
        InputSpec(DESCRIPTION_LOADED, Branch.FACT),
        InputSpec(OPERATION_FAILED, Branch.FACT),
        InputSpec(NEIGHBOUR_MISSING, Branch.FACT),
        InputSpec(INTERRUPTED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(
        EffectId("Reject"), EffectId("LoadCatalog"), EffectId("ResearchDescription"),
        EffectId("ResultDiscarded"), EffectId("PrepareRuntime"), EffectId("ApplyRuntime"),
    )

    // Rows follow `phases`, columns follow `inputs`. A change in flight refuses every intent but the
    // retry, so its rows accept only the facts of the change protocol, and the four facts that are
    // judged only after the fences — `OperationFailed`, `NeighbourMissing`, `Interrupted` and the
    // persistence fact — are accepted at every position after the load, whatever they name.
    override val accepts = acceptance(phases, inputs, listOf(
        //                               Cs Rt Ss Pn Pu Dp Sd Bc Bd Im Cl Rp Ra Ru Ini Cl Dl Of Nm It Pk
        /* new                       */ "000000000000001000001",
        /* idle-no-profile           */ "101100000110000001111",
        /* idle                      */ "101111111110000001111",
        /* loading-catalog           */ "101111101110000101111",
        /* describing                */ "101111111110000011111",
        /* change-preparing          */ "000000000001010001111",
        /* change-applying           */ "000000000000110001111",
        /* change-preparing-unknown  */ "010000000000010001111",
        /* change-applying-unknown   */ "010000000000010001111",
        /* persistence-unknown       */ "000000000000000000001",
    ))

    override fun label(state: SettingsMachine.State): PhaseId {
        val change = state.change
        return when {
            state.persistenceUnknown -> PERSISTENCE_UNKNOWN
            !state.initialized -> NEW
            change != null -> when (change.phase) {
                SettingsChangePhase.PREPARING -> if (change.unknown) CHANGE_PREPARING_UNKNOWN else CHANGE_PREPARING
                SettingsChangePhase.APPLYING -> if (change.unknown) CHANGE_APPLYING_UNKNOWN else CHANGE_APPLYING
            }
            state.catalogs.isNotEmpty() -> LOADING_CATALOG
            state.descriptions.isNotEmpty() -> DESCRIBING
            state.profiles.isEmpty() -> IDLE_NO_PROFILE
            else -> IDLE
        }
    }

    override fun name(input: SettingsMachine.Input): InputId = when (input) {
        is SettingsMachine.Intent.ChangeSettings -> CHANGE_SETTINGS
        is SettingsMachine.Intent.RetrySettingsChange -> RETRY_SETTINGS_CHANGE
        is SettingsMachine.Intent.SaveSettings -> SAVE_SETTINGS
        // One intent, two inputs: a new profile needs no reference, an existing one needs the one it saw.
        is SettingsMachine.Intent.SaveProfile -> if (input.expected == null) SAVE_PROFILE_NEW else SAVE_PROFILE_UPDATE
        is SettingsMachine.Intent.DeleteProfile -> DELETE_PROFILE
        is SettingsMachine.Intent.SaveDossier -> SAVE_DOSSIER
        is SettingsMachine.Intent.BeginCatalog -> BEGIN_CATALOG
        is SettingsMachine.Intent.BeginDescription -> BEGIN_DESCRIPTION
        is SettingsMachine.Intent.Import -> IMPORT
        is SettingsMachine.Intent.Clear -> CLEAR
        is SettingsMachine.Fact.RuntimePrepared -> RUNTIME_PREPARED
        is SettingsMachine.Fact.RuntimeApplied -> RUNTIME_APPLIED
        is SettingsMachine.Fact.RuntimeUnknown -> RUNTIME_UNKNOWN
        is SettingsMachine.Fact.Initialized -> INITIALIZED
        is SettingsMachine.Fact.CatalogLoaded -> CATALOG_LOADED
        is SettingsMachine.Fact.DescriptionLoaded -> DESCRIPTION_LOADED
        is SettingsMachine.Fact.OperationFailed -> OPERATION_FAILED
        is SettingsMachine.Fact.NeighbourMissing -> NEIGHBOUR_MISSING
        SettingsMachine.Fact.Interrupted -> INTERRUPTED
        SettingsMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: SettingsMachine.Effect): EffectId = when (effect) {
        is SettingsMachine.Effect.Reject -> EffectId("Reject")
        is SettingsMachine.Effect.LoadCatalog -> EffectId("LoadCatalog")
        is SettingsMachine.Effect.ResearchDescription -> EffectId("ResearchDescription")
        is SettingsMachine.Effect.ResultDiscarded -> EffectId("ResultDiscarded")
        is SettingsMachine.Effect.PrepareRuntime -> EffectId("PrepareRuntime")
        is SettingsMachine.Effect.ApplyRuntime -> EffectId("ApplyRuntime")
    }

    override fun unknown(state: SettingsMachine.State) = state.persistenceUnknown || state.change?.unknown == true

    override fun rejected(effect: SettingsMachine.Effect) = effect is SettingsMachine.Effect.Reject
}
