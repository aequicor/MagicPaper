package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDraftDeletionTest {
    @Test fun sessionEngineDraftRestoresAndCapturedCreateCannotClearANewerChoice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val projects = JsonCodingProjectRepository(fixture.kv, fixture.json)
            projects.save(CodingProject("p", "Проект", "/test/p", 1))
            // Match addCodingProject: an explicitly empty project has completed legacy migration.
            projects.sessions("p").forEach { projects.deleteSession("p", it.id) }
            val before = fixture.prepareCoding(QuestionnaireRuntime(), projects)
            assertNotNull(before.sessionCreationDraft("p")).update(CodingEngine.CODEX)
            before.close()
            assertTrue(projects.sessions("p").isEmpty(), "Opening and saving a form must not create a session")

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controlled = object : CodingProjectRepository by projects {
                override suspend fun saveSession(session: CodingSession) {
                    entered.complete(Unit); release.await(); projects.saveSession(session)
                }
            }
            val restored = fixture.prepareCoding(QuestionnaireRuntime(), controlled)
            try {
                val draft = assertNotNull(restored.sessionCreationDraft("p"))
                draft.awaitSaved()
                assertEquals(CodingEngine.CODEX, draft.state.value.value)
                var completed = 0
                restored.createCodingSession("p") { completed++ }
                entered.await()
                restored.createCodingSession("p") { completed++ }
                draft.update(CodingEngine.PI)
                release.complete(Unit)
                advanceUntilIdle()
                assertEquals(1, completed)
                assertEquals(CodingEngine.CODEX, projects.sessions("p").single().engine)
                assertEquals(CodingEngine.CODEX, fixture.settings.load().defaultCodingEngine)
                assertEquals(CodingEngine.PI, draft.state.value.value)
                assertNotNull(fixture.draftRepository.load("coding-session-create:p"), "A newer choice survives old acceptance")
                restored.createCodingSession("p") { completed++ }
                advanceUntilIdle()
                assertEquals(2, completed)
                assertEquals(setOf(CodingEngine.PI, CodingEngine.CODEX), projects.sessions("p").map { it.engine }.toSet())
                assertEquals(CodingEngine.PI, fixture.settings.load().defaultCodingEngine)
                assertNull(fixture.draftRepository.load("coding-session-create:p"))
                draft.update(CodingEngine.CODEX)
                draft.awaitSaved()
                var discarded = false
                restored.discardCodingSessionDraft("p") { discarded = true }
                advanceUntilIdle()
                assertTrue(discarded)
                assertNull(fixture.draftRepository.load("coding-session-create:p"))
                assertEquals(CodingEngine.PI, draft.state.value.value)
            } finally { release.complete(Unit); restored.close() }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failedSessionCreationKeepsTheEngineDraftAndCanBeRetried() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val projects = JsonCodingProjectRepository(fixture.kv, fixture.json)
            projects.save(CodingProject("p", "Проект", "/test/p", 1))
            // Keep the repository's legacy main-session migration outside this creation scenario.
            projects.sessions("p").forEach { projects.deleteSession("p", it.id) }
            var fails = true
            val controlled = object : CodingProjectRepository by projects {
                override suspend fun saveSession(session: CodingSession) {
                    if (fails) error("private-backend-token")
                    projects.saveSession(session)
                }
            }
            val service = fixture.prepareCoding(QuestionnaireRuntime(), controlled)
            try {
                val draft = assertNotNull(service.sessionCreationDraft("p"))
                draft.update(CodingEngine.CODEX); draft.awaitSaved()
                var completed = false
                service.createCodingSession("p") { completed = true }
                advanceUntilIdle()
                assertFalse(completed)
                assertTrue(projects.sessions("p").isEmpty())
                assertEquals(CodingEngine.CODEX, draft.state.value.value)
                assertNotNull(fixture.draftRepository.load("coding-session-create:p"))
                val status = assertNotNull(service.sessionCreationStatus.value["p"])
                assertFalse(status.busy)
                assertFalse(assertNotNull(status.error).contains("private"))
                fails = false
                service.createCodingSession("p") { completed = true }
                advanceUntilIdle()
                assertTrue(completed)
                assertEquals(1, projects.sessions("p").size)
                assertNull(fixture.draftRepository.load("coding-session-create:p"))
            } finally { service.close() }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun deletingChatsAfterRestartRemovesUnopenedDraftsAndRevokesOldEditors() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            fixture.seed()
            fixture.chats.save(ChatSession("unopened", "Другой чат", 2, 2))
            val before = fixture.prepareChat()
            before.composerDraft("first").update(ComposerDraftData("Первый черновик"))
            before.composerDraft("unopened").update(ComposerDraftData("Ещё не открывали"))
            before.close()

            val restored = fixture.prepareChat()
            try {
                val oldEditor = restored.composerDraft("first")
                oldEditor.awaitSaved()
                assertEquals("Первый черновик", oldEditor.state.value.value.text)
                restored.deleteSession("first")
                restored.deleteSession("unopened")
                advanceUntilIdle()
                oldEditor.update(ComposerDraftData("Поздняя запись"))
                restored.composerDraft("first").update(ComposerDraftData("Устаревший экран"))
                runCurrent()
                assertTrue(fixture.draftRepository.keys("chat:").isEmpty())
                assertNull(fixture.chats.session("first"))
                assertNull(fixture.chats.session("unopened"))
            } finally { restored.close() }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun sessionTreeAllSessionsAndProjectDeletionRemoveDormantQuestionnairesAndComposers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (action in listOf("tree", "all", "project")) {
                val fixture = ModelSettingsFixture()
                val projects = JsonCodingProjectRepository(fixture.kv, fixture.json)
                projects.save(CodingProject("p", "Проект", "/test/p", 1))
                projects.save(CodingProject("other", "Другой проект", "/test/other", 2))
                val sessions = listOf(
                    CodingSession("root", "p", "Главная", 1),
                    CodingSession("child", "p", "Дочерняя", 2, parentSessionId = "root"),
                    CodingSession("retained", "other", "Сохранить", 3),
                )
                sessions.forEach { projects.saveSession(it) }
                val runtime = QuestionnaireRuntime()
                val before = fixture.prepareCoding(runtime, projects)
                assertNotNull(before.sessionCreationDraft("p")).update(CodingEngine.CODEX)
                assertNotNull(before.sessionCreationDraft("other")).update(CodingEngine.CODEX)
                val question = PlanningQuestion("secret", "Ответ", QuestionKind.TEXT, secret = true)
                val requests = sessions.map { UserInteractionRequest("runtime:${it.id}", it.projectId,
                    it.id, InteractionKind.RUNTIME, listOf(question)) }
                runtime.questionnaires.value = requests
                runCurrent()
                sessions.forEach { before.composerDraft(it.id).text.value = "Черновик ${it.id}" }
                requests.forEach { before.updateQuestionnaireDraft(it.id,
                    QuestionnaireDraft(listOf(PlanningAnswer("secret", text = "Секретный ответ")))) }
                before.close()
                assertEquals(3, fixture.draftRepository.keys("questionnaire:").size)

                val restored = fixture.prepareCoding(QuestionnaireRuntime(), projects)
                try {
                    restored.activate("p", "root")
                    runCurrent()
                    val oldEditor = restored.composerDraft("root")
                    oldEditor.awaitSaved()
                    when (action) {
                        "tree" -> restored.deleteCodingSession("root")
                        "all" -> restored.deleteAllCodingSessions("p")
                        else -> restored.deleteCodingProject("p")
                    }
                    advanceUntilIdle()
                    oldEditor.text.value = "Поздняя запись"
                    restored.composerDraft("root").text.value = "Устаревший экран"
                    runCurrent()
                    assertEquals(listOf("coding:retained"), fixture.draftRepository.keys("coding:"), action)
                    val questionnaires = fixture.draftRepository.keys("questionnaire:")
                    assertEquals(1, questionnaires.size, action)
                    assertTrue(questionnaires.single().contains("runtime:retained"), action)
                    assertTrue(projects.sessions("p").isEmpty(), action)
                    assertEquals("Черновик retained", restored.composerDraft("retained").also { it.awaitSaved() }.text.value)
                    val creationKeys = fixture.draftRepository.keys("coding-session-create:")
                    assertEquals(if (action == "project") setOf("coding-session-create:other") else
                        setOf("coding-session-create:p", "coding-session-create:other"), creationKeys.toSet(), action)
                    if (action == "project") assertNull(restored.sessionCreationDraft("p"))
                } finally { restored.close() }
            }
        } finally { Dispatchers.resetMain() }
    }

    private class QuestionnaireRuntime : CodingRuntime {
        override val questionnaires = MutableStateFlow<List<UserInteractionRequest>>(emptyList())
        override val supported = true
        override val rootPath = "/test"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = emptyFlow<CodingEvent>()
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun uninstall() = Unit
    }
}
