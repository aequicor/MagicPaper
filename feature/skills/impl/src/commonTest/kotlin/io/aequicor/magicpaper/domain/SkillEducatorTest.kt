package io.aequicor.magicpaper.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException

class SkillEducatorTest {

    /** Шлюз без модели: любой вызов падает, как не подключённый сервер. */
    private class FailingGateway : LlmGateway {
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String =
            throw IllegalStateException("нет модели")
    }

    private val educator = SkillEducator(FailingGateway())
    private val messages = listOf(
        ChatMessage(id = "1", role = ChatRole.USER, text = "составь план переезда в новый офис", createdAt = 1L),
        ChatMessage(id = "2", role = ChatRole.AGENT, text = "Вот план…", createdAt = 2L),
    )

    @Test
    fun heuristicDraftWhenModelUnavailable() = runTest {
        val draft = educator.propose(messages, profile = null)
        assertTrue(draft.name.isNotBlank())
        assertTrue(draft.instructions.isNotBlank())
        assertTrue(draft.note != null)
    }

    @Test
    fun emptyHistoryStillProducesDraft() = runTest {
        val profile = LlmProfile(id = "p", name = "тест", baseUrl = "http://x/v1", modelId = "m")
        val draft = educator.propose(emptyList(), profile)
        assertTrue(draft.name.isNotBlank())
    }

    @Test fun providerFailureDoesNotBecomeSuccessfulHeuristicDraft() = runTest {
        val profile = LlmProfile(id = "p", name = "test", baseUrl = "https://example.invalid", modelId = "m", apiKey = "fixture")
        assertFailsWith<IllegalStateException> { educator.propose(messages, profile) }
    }

    @Test fun cancellationReachesTheRequestingOwner() = runTest {
        val cancelled = CancellationException("controlled")
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = throw cancelled
        }
        val profile = LlmProfile(id = "p", name = "test", baseUrl = "https://example.invalid", modelId = "m", apiKey = "fixture")
        assertSame(cancelled, assertFailsWith<CancellationException> { SkillEducator(gateway).propose(messages, profile) })
    }
}
