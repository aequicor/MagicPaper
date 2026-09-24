package io.aequicor.magicpaper.domain

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PlanUsageMonitorTest {
    private class Subscription(var read: suspend () -> OpenAiSubscriptionAccount) : OpenAiSubscriptionService {
        var reads = 0
        override suspend fun account(refreshToken: Boolean): OpenAiSubscriptionAccount { reads++; return read() }
        override suspend fun startLogin() = error("Unexpected login")
        override suspend fun awaitLogin(loginId: String) = error("Unexpected login")
        override suspend fun cancelLogin(loginId: String) = Unit
        override suspend fun logout() = Unit
        override fun close() = Unit
        override suspend fun models(profile: LlmProfile): List<ModelDefaults.DiscoveredModel> = error("Unexpected models")
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Unexpected completion")
    }

    private val limit = OpenAiRateLimit("codex", "codex", "primary", 30, 1_000, 300)

    @Test fun engineObservationsMergeWithoutLosingEarlierWindows() = runTest {
        val monitor = DefaultPlanUsageMonitor(this)
        val week = PlanUsageWindow("seven_day", .1f, 10_080)
        monitor.observe(PlanUsage(ProviderType.ANTHROPIC_SUBSCRIPTION, listOf(week, PlanUsageWindow("five_hour", .2f, 300))))
        monitor.observe(PlanUsage(ProviderType.ANTHROPIC_SUBSCRIPTION, limited = true))
        val usage = monitor.state.value.getValue(ProviderType.ANTHROPIC_SUBSCRIPTION)
        assertEquals(listOf("seven_day", "five_hour"), usage.windows.map { it.id })
        assertTrue(usage.limited)
    }

    @Test fun refreshReadsTheChatGptAccountAtMostOncePerInterval() = runTest {
        var clock = 0L
        val subscription = Subscription { OpenAiSubscriptionAccount(true, planType = "pro", rateLimits = listOf(limit)) }
        val monitor = DefaultPlanUsageMonitor(this, subscription, now = { clock }, minInterval = 1_000)
        monitor.refresh(ProviderType.ANTHROPIC_SUBSCRIPTION)
        monitor.refresh(ProviderType.OPENAI_SUBSCRIPTION)
        monitor.refresh(ProviderType.OPENAI_SUBSCRIPTION)
        advanceUntilIdle()
        assertEquals(1, subscription.reads)
        assertEquals(PlanUsage(ProviderType.OPENAI_SUBSCRIPTION, listOf(limit.planWindow()), "pro", observedAt = 0),
            monitor.state.value[ProviderType.OPENAI_SUBSCRIPTION])
        clock = 1_000
        monitor.refresh(ProviderType.OPENAI_SUBSCRIPTION)
        advanceUntilIdle()
        assertEquals(2, subscription.reads)
    }

    @Test fun failedRefreshKeepsTheLastFiguresAsStaleAndSignOutClearsThem() = runTest {
        var clock = 0L
        val subscription = Subscription { OpenAiSubscriptionAccount(true, rateLimits = listOf(limit)) }
        val monitor = DefaultPlanUsageMonitor(this, subscription, now = { clock }, minInterval = 1)
        monitor.refresh(ProviderType.OPENAI_SUBSCRIPTION); advanceUntilIdle()
        subscription.read = { error("app-server stopped") }
        clock = 10; monitor.refresh(ProviderType.OPENAI_SUBSCRIPTION); advanceUntilIdle()
        val stale = monitor.state.value.getValue(ProviderType.OPENAI_SUBSCRIPTION)
        assertTrue(stale.stale)
        assertEquals(listOf(limit.planWindow()), stale.windows)
        subscription.read = { OpenAiSubscriptionAccount(true, rateLimitsUnavailable = true) }
        clock = 20; monitor.refresh(ProviderType.OPENAI_SUBSCRIPTION); advanceUntilIdle()
        assertEquals(listOf(limit.planWindow()), monitor.state.value.getValue(ProviderType.OPENAI_SUBSCRIPTION).windows)
        subscription.read = { OpenAiSubscriptionAccount(false) }
        clock = 30; monitor.refresh(ProviderType.OPENAI_SUBSCRIPTION); advanceUntilIdle()
        assertNull(monitor.state.value[ProviderType.OPENAI_SUBSCRIPTION])
    }
}
