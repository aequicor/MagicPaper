package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.StateFlow

/** Semantic writes are committed before returned effects may be interpreted by the application owner. */
interface CodingProjectCommands {
    val states: StateFlow<Map<String, CodingMachine.State>>
    val failures: StateFlow<Map<String, String>>
    suspend fun start()
    suspend fun dispatch(projectId: String, input: CodingMachine.Input): CodingMachine.Transition
    /** The application first closes admission and joins all effect producers. */
    suspend fun wipe()
}

/** The durable owner is passed only to coordinators that both query and issue semantic commands. */
interface CodingProjectOwner : CodingProjectRepository, CodingProjectCommands

suspend fun CodingProjectCommands.acceptSession(session: CodingSession, input: CodingMachine.Input): CodingSession =
    checkNotNull(dispatch(session.projectId, input).state.sessions[session.id]) { "Сессия удалена" }

/** Private immutable input bytes are separate from the journal's safe identity envelope. */
interface CodingPayloadStore {
    suspend fun save(projectId: String, inputId: String, input: CodingMachine.Input): CodingInputRef
    suspend fun read(ref: CodingInputRef): CodingMachine.Input
    /** The inputs of [refs], in order; a store that keeps one record per input may read them together. */
    suspend fun readAll(refs: List<CodingInputRef>): List<CodingMachine.Input> = refs.map { read(it) }
    suspend fun clear()
}

@kotlinx.serialization.Serializable
data class CodingInputRef(val projectId: String, val inputId: String, val kind: String, val digest: String)

/** Legacy session/history paths are projections. Only the journal owner may write this cache. */
interface CodingCheckpointStore : CodingProjectRepository {
    suspend fun legacyProjects(excludingIds: Set<String>): List<CodingMachine.Fact.LegacyImported>
    suspend fun checkpoint(state: CodingMachine.State)
    suspend fun wipe()
}
