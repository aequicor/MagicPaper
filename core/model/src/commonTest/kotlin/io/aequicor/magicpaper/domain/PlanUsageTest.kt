package io.aequicor.magicpaper.domain

import kotlinx.datetime.TimeZone
import kotlin.test.*

class PlanUsageTest {
    private val week = PlanUsageWindow("seven_day", .2f, 10_080, 100)
    private val hours = PlanUsageWindow("five_hour", .5f, 300, 50)

    @Test fun rollingUpdateReplacesItsWindowsAndKeepsTheRest() {
        val before = PlanUsage(ProviderType.OPENAI_SUBSCRIPTION, listOf(week, hours), plan = "pro", observedAt = 1)
        val merged = before.merge(PlanUsage(ProviderType.OPENAI_SUBSCRIPTION, listOf(hours.copy(usedFraction = .6f)), observedAt = 2))
        assertEquals(listOf(week, hours.copy(usedFraction = .6f)), merged.windows)
        assertEquals("pro", merged.plan)
        assertEquals(2, merged.observedAt)
        assertEquals(listOf("five_hour", "seven_day"), merged.orderedWindows.map { it.id })
    }

    @Test fun titlesNameTheSpanAndTheScopeOnlyBesideAScopedWindow() {
        val plain = PlanUsage(ProviderType.ANTHROPIC_SUBSCRIPTION, listOf(week, hours), plan = "max")
        assertEquals("Лимиты Claude · Max", plain.title())
        assertEquals("Неделя", plain.windowTitle(week))
        assertEquals("5 часов", plain.windowTitle(hours))
        assertEquals(listOf("1 час", "3 часа", "12 часов", "21 час"),
            listOf(60L, 180L, 720L, 1260L).map { plain.windowTitle(hours.copy(durationMinutes = it)) })
        val scoped = plain.copy(windows = plain.windows + week.copy(id = "seven_day_opus", scope = "Opus"))
        assertEquals("Неделя · все модели", scoped.windowTitle(week))
        assertEquals("5 часов", scoped.windowTitle(hours))
        assertEquals("Неделя · Opus", scoped.windowTitle(scoped.windows.last()))
        assertEquals("Лимиты ChatGPT · Self Serve Business", PlanUsage(ProviderType.OPENAI_SUBSCRIPTION, plan = "self_serve_business").title())
        assertEquals("Лимиты ChatGPT", PlanUsage(ProviderType.OPENAI_SUBSCRIPTION, plan = "unknown").title())
        assertEquals("Основной лимит", plain.windowTitle(PlanUsageWindow("codex:primary", 0f)))
    }

    @Test fun resetIsACountdownWithinADayAndALocalWeekdayBeyond() {
        val now = 1_790_252_000_000L // Thursday 2026-09-24 12:13:20 UTC
        fun at(seconds: Long) = PlanUsageWindow("w", 0f, resetsAtEpochSeconds = now / 1000 + seconds)
        assertEquals("Сброс через 4 ч 45 мин", at(4 * 3600 + 45 * 60).resetText(now, TimeZone.UTC))
        assertEquals("Сброс через 2 ч", at(2 * 3600).resetText(now, TimeZone.UTC))
        assertEquals("Сброс через 1 мин", at(1).resetText(now, TimeZone.UTC))
        assertEquals("Сброс ожидается", at(-60).resetText(now, TimeZone.UTC))
        assertEquals("Сброс в пн, 12:13",at(4 * 86_400).resetText(now, TimeZone.UTC))
        assertNull(PlanUsageWindow("w", 0f).resetText(now))
    }

    @Test fun chatGptBucketsBecomePlanWindows() {
        val general = OpenAiRateLimit("codex", "codex", "primary", 42, 1_000, 300)
        assertEquals(PlanUsageWindow("codex:primary", .42f, 300, 1_000), general.planWindow())
        assertEquals("GPT-5.3-Codex-Spark", OpenAiRateLimit("codex_bengalfox", "GPT-5.3-Codex-Spark", "secondary", 150).planWindow().scope)
        assertEquals(1f, OpenAiRateLimit("codex_bengalfox", "Spark", "secondary", 150).planWindow().usedFraction)
        val account = OpenAiSubscriptionAccount(true, planType = "plus", rateLimits = listOf(general), limitReached = true)
        assertEquals(PlanUsage(ProviderType.OPENAI_SUBSCRIPTION, listOf(general.planWindow()), "plus", true, 9), account.planUsage(9))
        assertNull(account.copy(rateLimitsUnavailable = true).planUsage(9))
        assertNull(OpenAiSubscriptionAccount(false).planUsage(9))
    }
}
