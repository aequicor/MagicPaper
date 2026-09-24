package io.aequicor.magicpaper.domain

import kotlin.test.*

class PlanningRetryPolicyTest {
    @Test fun onlyLocalChecksCanResumeWithoutExternalReview() {
        assertTrue(PlanningRetryPolicy.localCheck("npm test"))
        assertTrue(PlanningRetryPolicy.localCheck("./gradlew :feature:session:impl:jvmTest"))
        assertFalse(PlanningRetryPolicy.localCheck("npm test && npm publish"))
        assertFalse(PlanningRetryPolicy.localCheck("curl https://example.com"))
    }
    @Test fun backoffIsBoundedAndHonoursProviderDelay() {
        assertEquals(2000L, PlanningRetryPolicy.delayMillis(1))
        assertEquals(5000L, PlanningRetryPolicy.delayMillis(2))
        assertEquals(15000L, PlanningRetryPolicy.delayMillis(3))
        assertEquals(60_250L, PlanningRetryPolicy.delayMillis(1, "60", jitter = 250))
        assertEquals(15_000L, PlanningRetryPolicy.delayMillis(4))
        assertEquals(15_000L, PlanningRetryPolicy.delayMillis(Int.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> { PlanningRetryPolicy.delayMillis(0) }
    }
    @Test fun httpDateAndMalformedHeader() {
        assertEquals(1000L, PlanningRetryPolicy.retryAfterMillis("Thu, 01 Jan 1970 00:00:01 GMT", 0))
        assertEquals(0L, PlanningRetryPolicy.retryAfterMillis("invalid", 0))
        assertEquals("120", PlanningRetryPolicy.fromMessage("HTTP 429\nRetry-After: 120"))
    }

    private fun issue(kind: IssueKind, message: String = "сбой", requiresUser: Boolean = false) =
        PlanningIssue(kind, message, requiresUser = requiresUser)

    private fun decide(issue: PlanningIssue, completed: Int = 0, limit: Int? = 3) =
        PlanningRetryPolicy.decide(issue, completed, limit, now = 1_000L, jitter = 0L)

    @Test fun onlyATransientCauseRepeatsItself() {
        // Repeating a missing model or an unconfirmed effect is the same failure at a slower
        // rate; the issue that came in already names who has to act.
        IssueKind.entries.filter { it != IssueKind.TRANSIENT }.forEach {
            assertEquals(RetryDecision.NotTransient, decide(issue(it)), it.name)
        }
        assertEquals(RetryDecision.Again(1, 3_000L), decide(issue(IssueKind.TRANSIENT)))
    }

    @Test fun aCauseThatNeedsAPersonKeepsWhoeverItNamed() {
        // A verification verdict may leave the plan running; forcing it to a person would
        // stop a plan that was only told its last check did not pass.
        val running = issue(IssueKind.VERIFICATION, requiresUser = false)
        assertEquals(running, decide(running).applyTo(running))
        val blocking = issue(IssueKind.CONFIGURATION, requiresUser = true)
        assertEquals(blocking, decide(blocking).applyTo(blocking))
    }

    @Test fun aSpentBudgetEndsAtAPersonAndKeepsTheCount() {
        val spent = decide(issue(IssueKind.TRANSIENT), completed = 3, limit = 3)
        assertEquals(RetryDecision.Exhausted(3), spent)
        val recorded = spent.applyTo(issue(IssueKind.TRANSIENT))
        assertTrue(recorded.requiresUser, "Исчерпанный бюджет останавливает автоматику")
        assertEquals(3, recorded.retries, "Человек должен видеть, сколько попыток уже потрачено")
        assertEquals(0L, recorded.retryAt, "Остановленная попытка не назначает себе время")
    }

    @Test fun anAbsentLimitNeverExhausts() {
        // The limit is a user's choice. Not having made it must not end recovery.
        assertEquals(RetryDecision.Again(1_000_001, 16_000L), decide(issue(IssueKind.TRANSIENT), completed = 1_000_000, limit = null))
    }

    @Test fun thePermittedRetryCarriesItsOwnScheduleAndClearsNothingElse() {
        val transient = issue(IssueKind.TRANSIENT)
        val applied = decide(transient, completed = 1).applyTo(transient)
        assertEquals(2, applied.retries)
        assertEquals(1_000L + 5_000L, applied.retryAt, "Пауза отсчитывается от переданных часов")
        assertFalse(applied.requiresUser, "Планировщик возьмёт попытку сам")
        assertEquals(transient.message, applied.message)
    }

    @Test fun theProvidersOwnDelayWinsOverTheBackoff() {
        val limited = issue(IssueKind.TRANSIENT, "HTTP 429\nRetry-After: 60")
        assertEquals(RetryDecision.Again(1, 1_000L + 60_000L), decide(limited))
    }

    @Test fun theSameFailureAtTheSameClockAlwaysDecidesTheSame() {
        val transient = issue(IssueKind.TRANSIENT)
        val once = PlanningRetryPolicy.decide(transient, 0, 3, now = 42L, jitter = 100L)
        assertEquals(once, PlanningRetryPolicy.decide(transient, 0, 3, now = 42L, jitter = 100L))
    }

    @Test fun aReceivedRejectionIsConfirmedWhileALostAnswerIsNot() {
        // Отказ, полученный от провайдера, доказывает отсутствие ответа: повтор не удваивает эффект.
        listOf(400, 401, 403, 404, 422, 429).forEach { status ->
            assertTrue(transport(status).confirmedRejection, "HTTP $status")
        }
        // Потерянный ответ (408, 5xx) может оказаться уже созданной и оплаченной генерацией.
        listOf(408, 500, 502, 503, 504).forEach { status ->
            assertFalse(transport(status).confirmedRejection, "HTTP $status")
        }
    }

    @Test fun theTransportRejectionIsFoundThroughWrappersAndWithoutACycle() {
        val rejection = transport(429)
        assertSame(rejection, rejection.transportRejection())
        assertSame(rejection, IllegalStateException("обёртка", IllegalStateException("внешняя", rejection)).transportRejection())
        assertNull(IllegalStateException("без обращения к провайдеру").transportRejection())
        assertNull(CyclicCause().transportRejection(), "Петля причин не должна останавливать обход")
        var deep: Throwable = rejection
        repeat(20) { deep = IllegalStateException("уровень $it", deep) }
        assertNull(deep.transportRejection(), "Обход цепочки ограничен")
    }

    private class CyclicCause : IllegalStateException("цикл") { override val cause: Throwable get() = this }

    /**
     * Z.AI отвечает статусом 429 и на «повторите позже» (коды 1302/1305), и на отказ в доступе
     * к модели (код 1113 «Insufficient balance or no resource package»). Для планировщика это
     * разные события: второе не лечится ни ожиданием, ни повтором.
     */
    @Test fun anEntitlementRefusalStopsAutomaticRetriesWhileARateLimitDoesNot() {
        assertTrue(transport(429, ProviderRejection(code = "1113", refusal = ProviderRefusal.ENTITLEMENT)).blocksAutomaticRetry)
        assertFalse(transport(429, ProviderRejection(code = "1302")).blocksAutomaticRetry)
        assertFalse(transport(429).blocksAutomaticRetry)
        assertFalse(transport(503).blocksAutomaticRetry)
        assertFalse(transport(400, ProviderRejection(param = "top_p", refusal = ProviderRefusal.PARAMETER)).blocksAutomaticRetry)
    }

    private fun transport(status: Int, rejection: ProviderRejection? = null) =
        LlmTransportException(status, null, "тело ответа провайдера", rejection)
}
