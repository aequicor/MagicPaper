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
}
