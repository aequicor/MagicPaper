package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.DefaultSessionOrganismStore
import io.aequicor.magicpaper.data.coding.journalCodingProjects
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.serialization.json.*

internal val boundedOrganismTestLimits = OrganismLimits(activeSessions = 8, depth = 6, tokens = 1_000_000,
    recoveryTokens = 10_000, durationMillis = 3_600_000, retries = 3, queueSize = 128, contextCharacters = 64_000)

internal class SessionOrganismTestFixture(
    backingProjects: CodingProjectOwner? = null,
    val storage: KeyValueStore = InMemoryKeyValueStore(),
    val limits: OrganismLimits = boundedOrganismTestLimits,
    val project: CodingProject = CodingProject("project", "Project", "/fixture", 1),
    val rootName: String = "Зигота",
    val sourceSnapshot: suspend (CodingProject) -> String? = { null },
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    var journal = InMemoryEventJournal()
    var projects: CodingProjectOwner = backingProjects ?: journalCodingProjects(storage, json, journal)
    val profiles = JsonLlmProfileRepository(storage, json)
    val settings = JsonSettingsRepository(storage, json)
    var store = DefaultSessionOrganismStore(storage, journal, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined) { 1_000 }
    val ports = TestOrganismPorts()
    var service = testOrganismService(store, projects, settings, ports, sourceSnapshot)
    lateinit var root: CodingSession

    suspend fun initialize(mode: CodingInteractionMode = CodingInteractionMode.RESEARCH) {
        settings.save(settings.load().copy(agentLimits = limits))
        projects.dispatch(project.id, CodingMachine.Intent.CreateProject(project))
        val saved = CodingSession("root", project.id, rootName, 1, engine = CodingEngine.PI,
            planningMode = mode == CodingInteractionMode.PLANNING, researchMode = mode == CodingInteractionMode.RESEARCH)
        projects.dispatch(project.id, CodingMachine.Intent.CreateSession(saved))
        val organism = service.ensure(saved)
        root = projects.sessions(project.id).single { it.id == organism.zygoteId }
    }

    /** Construct a pre-journal disk fixture, then import it through both real owners. */
    suspend fun reopenLegacy(snapshot: SessionOrganism) {
        service.shutdown()
        storage.write("session-organism-${snapshot.id}", json.encodeToString(SessionOrganism.serializer(), snapshot))
        journal = InMemoryEventJournal()
        projects = journalCodingProjects(storage, json, journal)
        store = DefaultSessionOrganismStore(storage, journal, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined) { 1_000 }
        service = testOrganismService(store, projects, settings, ports, sourceSnapshot)
    }

    suspend fun context(id: String = root.id): ToolExecutionContext = ToolExecutionContext.worker(projects.sessions(project.id).single { it.id == id })
    suspend fun create(id: String, parent: String = root.id) = service.execute(context(parent), id, "session.create",
        json.encodeToJsonElement(SessionCreateArgs("Child", "Investigate project", "Document verified findings", 1_000)).jsonObject)
    suspend fun send(id: String, recipient: String, sender: String = root.id) = service.execute(context(sender), id, "session.send",
        json.encodeToJsonElement(SessionSendArgs(recipient, SessionContextPacket("Context for recipient", sourceVersion = "source-sha",
            ruleVersion = root.planningRulesSnapshot?.version.orEmpty(), attachments = listOf("artifact:1"), summarized = true, omissions = "large logs"))).jsonObject)
}
