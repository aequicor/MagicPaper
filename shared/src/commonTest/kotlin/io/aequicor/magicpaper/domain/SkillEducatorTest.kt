package io.aequicor.magicpaper.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SkillEducatorTest {

    /** Шлюз без модели: любой вызов падает, как нес настроенный сервер. */
    private class FailingGateway : LlmGateway {
        override suspend fun complete(settings: AppSettings, messages: List<LlmMessage>): String =
            throw IllegalStateException("нет модели")
    }

    private val educator = SkillEducator(FailingGateway())
    private val messages = listOf(
        ChatMessage(id = "1", role = ChatRole.USER, text = "составь план переезда в новый офис", createdAt = 1L),
        ChatMessage(id = "2", role = ChatRole.AGENT, text = "Вот план…", createdAt = 2L),
    )

    @Test
    fun heuristicDraftWhenModelUnavailable() = runTest {
        val draft = educator.propose(messages, AppSettings(llmBaseUrl = ""))
        assertTrue(draft.name.isNotBlank())
        assertTrue(draft.instructions.isNotBlank())
        assertTrue(draft.note != null)
    }

    @Test
    fun emptyHistoryStillProducesDraft() = runTest {
        val draft = educator.propose(emptyList(), AppSettings(llmBaseUrl = ""))
        assertTrue(draft.name.isNotBlank())
    }
}
