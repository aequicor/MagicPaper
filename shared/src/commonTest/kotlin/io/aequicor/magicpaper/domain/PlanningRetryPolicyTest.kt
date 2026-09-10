package io.aequicor.magicpaper.domain

import kotlin.test.*

class PlanningRetryPolicyTest {
    @Test fun onlyLocalChecksCanResumeWithoutExternalReview() {
        assertTrue(PlanningRetryPolicy.localCheck("npm test"))
        assertTrue(PlanningRetryPolicy.localCheck("./gradlew :shared:jvmTest"))
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
}
