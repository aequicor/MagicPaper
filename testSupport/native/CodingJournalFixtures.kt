package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.CodingCheckpointStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json

/** Seed legacy cache bytes before calling start; subsequent changes use typed machine inputs. */
fun journalCodingProjects(
    store: KeyValueStore,
    json: Json = Json { encodeDefaults = true },
    journal: EventJournal = InMemoryEventJournal(),
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    checkpoints: CodingCheckpointStore = JsonCodingProjectRepository(store, json),
): CodingJournalStore = CodingJournalStore(checkpoints, journal, StoredCodingPayloads(store, json, dispatcher), json, dispatcher)

suspend fun io.aequicor.magicpaper.domain.CodingProjectOwner.createTestProject(project: io.aequicor.magicpaper.domain.CodingProject) {
    dispatch(project.id, io.aequicor.magicpaper.domain.CodingMachine.Intent.CreateProject(project))
}
suspend fun io.aequicor.magicpaper.domain.CodingProjectOwner.createTestSession(session: io.aequicor.magicpaper.domain.CodingSession) {
    dispatch(session.projectId, io.aequicor.magicpaper.domain.CodingMachine.Intent.CreateSession(session.copy(engine = session.engine ?: io.aequicor.magicpaper.domain.CodingEngine.PI)))
}

/** Test setup still crosses the real closed history command boundary. */
suspend fun io.aequicor.magicpaper.domain.CodingProjectOwner.publishTestHistory(projectId: String, sessionId: String,
    messages: List<io.aequicor.magicpaper.domain.CodingMessage>) {
    val session = sessions(projectId).single { it.id == sessionId }
    dispatch(projectId, io.aequicor.magicpaper.domain.CodingMachine.Fact.HistoryPublished(
        io.aequicor.magicpaper.domain.CodingMachine.ref(session), messages))
}

/** Seed a coordinator fixture by recording its real semantic events, never by replacing its snapshot. */
suspend fun io.aequicor.magicpaper.domain.CodingProjectOwner.recordTestOrchestration(value: io.aequicor.magicpaper.domain.OrchestrationState) {
    val session = sessions(value.projectId).single { it.id == value.sessionId }
    suspend fun record(event: io.aequicor.magicpaper.domain.planning.OrchestrationEvent) {
        dispatch(value.projectId, io.aequicor.magicpaper.domain.CodingMachine.Intent.Orchestrate(
            io.aequicor.magicpaper.domain.CodingMachine.ref(session), event))
    }
    value.activePlanId?.let { record(io.aequicor.magicpaper.domain.planning.OrchestrationEvent.PlanSelected(it)) }
    value.inputs.forEach { record(io.aequicor.magicpaper.domain.planning.OrchestrationEvent.InputEnqueued(it)) }
    value.questions.forEach { record(io.aequicor.magicpaper.domain.planning.OrchestrationEvent.QuestionRegistered(it)) }
    value.workPauses.forEach { (id, pause) -> record(io.aequicor.magicpaper.domain.planning.OrchestrationEvent.WorkPaused(id, pause)) }
    value.sessionCommands.forEach { record(io.aequicor.magicpaper.domain.planning.OrchestrationEvent.SessionCommandRegistered(it)) }
}
