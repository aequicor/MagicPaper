package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.CodingCommandRejected
import io.aequicor.magicpaper.data.coding.CodingJournalStore
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.journalCodingProjects
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * A launch reports what ended it: an outcome nobody confirmed, or its owner's stop. It does not report a refusal that
 * followed from either, or a storage failure that did not happen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodingUnconfirmedOutcomeNoticeTest {
    private val project = CodingProject("project", "Project", "/fixture", 1)
    private val session = CodingSession("session", project.id, "Task", 1, engine = CodingEngine.PI)
    private val unconfirmed = "Исход предыдущего запуска не подтверждён. Проверьте сохранённый результат перед новым запросом."

    private class Runtime(val failure: suspend () -> Nothing) : CodingRuntime {
        var calls = 0
        override val supported = true
        override val rootPath = "/fixture"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override suspend fun uninstall() = Unit
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow<CodingEvent> {
            calls++
            failure()
        }
    }

    private fun recovery() = NativeRunRecoveryRequired(NativeRunRecoverySnapshot(listOf(NativeRunRecoveryItem(
        NativeRunRecoveryRef(CodingEngine.PI, session.id, "request", 0), NativeRunOutcome.UNKNOWN, NativeRunTermination.STOPPED, null)), false))

    private suspend fun owner(f: ModelSettingsFixture, saved: CodingSession = session): CodingJournalStore {
        val cache = JsonCodingProjectRepository(f.kv, f.json).also { it.save(project); it.saveSession(saved) }
        return journalCodingProjects(f.kv, f.json, f.chatJournal, Dispatchers.Main, cache)
    }

    @Test fun runStoppedElsewhereReportsTheUnconfirmedOutcomeInsteadOfASaveFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val owner = owner(f)
            // As the session tree delivers it: another owner stopped the run, the native binding was refused,
            // and cleanup retained the unconfirmed outcome on that refusal.
            val runtime = Runtime {
                val run = checkNotNull(owner.states.value[project.id]?.runs?.get(session.id))
                owner.dispatch(project.id, CodingMachine.Fact.RunStopped(run.ref, unknown = true))
                throw CodingCommandRejected("Запуск уже остановлен").apply { addSuppressed(recovery()) }
            }
            model = f.prepareCoding(runtime, owner); runCurrent()
            model.sendCodingPromptTo(session.id, "Request"); runCurrent()

            assertEquals(1, runtime.calls)
            assertEquals(unconfirmed, model.state.value.notice)
            assertEquals(CodingMachine.Phase.UNKNOWN, model.state.value.coding.currentSession!!.runPhase)
        } finally { model?.close(); Dispatchers.resetMain() }
    }

    @Test fun stopBeforeTheEngineStartedEndsAsAStopWithoutAFalseSaveFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); val owner = owner(f)
            // As the session tree ends a launch its owner stopped before it began: a known stop, then cancellation.
            val runtime = object : CodingRuntime by Runtime({ error("unused") }) {
                override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?,
                    attachments: List<Attachment>) = flow<CodingEvent> {
                    emit(CodingEvent.Notice("Подготовка worktree"))
                    val run = checkNotNull(owner.states.value[project.id]?.runs?.get(session.id))
                    owner.dispatch(project.id, CodingMachine.Fact.RunStopped(run.ref, unknown = false))
                    throw CancellationException("Сессия остановлена до начала запуска")
                }
            }
            model = f.prepareCoding(runtime, owner); runCurrent()
            model.sendCodingPromptTo(session.id, "Request"); runCurrent()

            assertNull(model.state.value.notice)
            assertEquals(CodingMachine.Phase.INTERRUPTED, model.state.value.coding.currentSession!!.runPhase)
            assertFalse(model.state.value.coding.currentSession!!.running)
        } finally { model?.close(); Dispatchers.resetMain() }
    }

    @Test fun directlyUnconfirmedOutcomeIsTheSavedReason() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: DefaultCodingService? = null
        try {
            // Without a worktree an IllegalStateException's own message is not shown; this outcome must still be.
            val f = ModelSettingsFixture(); val owner = owner(f, session.copy(worktreeEnabled = false))
            val runtime = Runtime { throw recovery() }
            model = f.prepareCoding(runtime, owner); runCurrent()
            model.sendCodingPromptTo(session.id, "Request"); runCurrent()

            assertEquals(1, runtime.calls)
            val response = owner.messages(project.id, session.id).single { it.role == CodingRole.AGENT }
            assertTrue(response.failed)
            assertContains(response.text, unconfirmed)
        } finally { model?.close(); Dispatchers.resetMain() }
    }
}
