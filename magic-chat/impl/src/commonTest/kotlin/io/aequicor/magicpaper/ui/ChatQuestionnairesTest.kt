package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.storage.InMemoryDraftRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatQuestionnairesTest {
    private fun request(id: String = "request", sessionId: String = "chat", secret: Boolean = false) =
        UserInteractionRequest(id, "", sessionId, InteractionKind.RUNTIME, listOf(
            PlanningQuestion("answer", "Что учесть?", secret = secret, canSkip = false)))
    private val answer = listOf(PlanningAnswer("answer", text = "Keep diagrams"))

    private class Backend(request: UserInteractionRequest) : ChatBackend {
        override val questionnaires = MutableStateFlow(listOf(request))
        val answers = mutableListOf<Pair<String, List<PlanningAnswer>>>()
        var rejection = false
        var gate: CompletableDeferred<Unit>? = null
        override fun abort(sessionId: String) = Unit
        override suspend fun respondQuestionnaire(id: String, answers: List<PlanningAnswer>) {
            if (rejection) error("transport unavailable")
            gate?.await()
            this.answers += id to answers
            questionnaires.value = questionnaires.value.filterNot { it.sourceId == id }
        }
    }

    @Test fun restoringDraftNeverAnswersAndExplicitResponseSurvivesNavigation() = runTest {
        val repository = InMemoryDraftRepository()
        val backend = Backend(request())
        val firstScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val first = ChatQuestionnaires(backend, repository, firstScope) { error(it) }
        first.start()
        first.updateDraft("request", QuestionnaireDraft(answer))
        first.flush()
        firstScope.cancel()
        val restored = ChatQuestionnaires(backend, repository, backgroundScope) { error(it) }
        restored.start()
        runCurrent()
        assertEquals(answer, restored.drafts.value["request"]?.answers)
        assertTrue(backend.answers.isEmpty())
        restored.submit("request", answer)
        runCurrent()
        assertEquals(listOf("request" to answer), backend.answers)
        assertTrue(restored.requests.value.isEmpty())
    }

    @Test fun failedResponseKeepsDraftAndAllowsExplicitRetryWithoutDuplicatingSubmit() = runTest {
        val backend = Backend(request()).apply { rejection = true }
        val controller = ChatQuestionnaires(backend, InMemoryDraftRepository(), backgroundScope) { error(it) }
        controller.start(); runCurrent()
        controller.updateDraft("request", QuestionnaireDraft(answer))
        controller.submit("request", answer); runCurrent()
        assertNotNull(controller.requests.value.single().error)
        assertEquals(answer, controller.drafts.value["request"]?.answers)
        assertTrue(backend.answers.isEmpty())
        backend.rejection = false
        val gate = CompletableDeferred<Unit>(); backend.gate = gate
        controller.submit("request", answer); runCurrent()
        controller.submit("request", answer)
        assertTrue(controller.requests.value.single().submitting)
        gate.complete(Unit); runCurrent()
        assertEquals(1, backend.answers.size)
    }

    @Test fun changedGenerationDoesNotReusePreviousDraftOrAnswer() = runTest {
        val backend = Backend(request())
        val controller = ChatQuestionnaires(backend, InMemoryDraftRepository(), backgroundScope) { error(it) }
        controller.start(); runCurrent()
        controller.updateDraft("request", QuestionnaireDraft(answer)); runCurrent()
        backend.questionnaires.value = listOf(request().copy(runtimeGeneration = 2))
        runCurrent()
        assertTrue(controller.drafts.value.getValue("request").answers.isEmpty())
        assertTrue(backend.answers.isEmpty())
    }

    @Test fun missingOrInvalidAnswersDoNotReachBackend() = runTest {
        val backend = Backend(request())
        val controller = ChatQuestionnaires(backend, InMemoryDraftRepository(), backgroundScope) { error(it) }
        controller.start(); runCurrent()
        controller.submit("missing", answer)
        controller.submit("request", emptyList()); runCurrent()
        assertTrue(backend.answers.isEmpty())
        assertNotNull(controller.requests.value.single().error)
    }

    @Test fun secretDraftUsesProtectedFieldsWithoutPuttingAnswerInPayload() = runTest {
        val backend = Backend(request(secret = true))
        val repository = InMemoryDraftRepository()
        val controller = ChatQuestionnaires(backend, repository, backgroundScope) { error(it) }
        controller.start(); runCurrent()
        controller.updateDraft("request", QuestionnaireDraft(answer)); controller.flush()
        val record = checkNotNull(repository.load(repository.keys("chat-questionnaire:").single()))
        assertFalse(record.payload.contains("Keep diagrams"))
        assertTrue(record.secrets.getValue("answer").contains("Keep diagrams"))
        assertTrue(backend.answers.isEmpty())
    }

    @Test fun deletingOneConversationRevokesItsDraftAndLeavesOtherRequestUntouched() = runTest {
        val backend = Backend(request()).apply { questionnaires.value += request("other", "other-chat") }
        val repository = InMemoryDraftRepository()
        val controller = ChatQuestionnaires(backend, repository, backgroundScope) { error(it) }
        controller.start(); runCurrent()
        controller.updateDraft("request", QuestionnaireDraft(answer))
        controller.updateDraft("other", QuestionnaireDraft(answer)); controller.flush()
        controller.removeSession("chat")
        controller.submit("request", answer); runCurrent()
        assertEquals(listOf("other"), controller.requests.value.map { it.id })
        assertEquals(answer, controller.drafts.value["other"]?.answers)
        assertTrue(backend.answers.isEmpty())
    }
}
