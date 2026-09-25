package io.aequicor.magicpaper.data.claude

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudePlanUsageQueryTest {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    @Test fun controlRequestReturnsCurrentPlanWithoutSendingAModelPrompt() = runBlocking {
        if (windows) return@runBlocking
        val home = Files.createTempDirectory("claude-plan-usage").toFile()
        try {
            val binary = home.resolve("claude")
            binary.writeText("#!/bin/sh\nD=\"${home.path}\"\nprintf '%s\\n' \"\$@\" > \"\$D/args\"\ncat > \"\$D/stdin\"\ncat <<'JSON'\n" +
                """{"type":"control_response","response":{"subtype":"success","request_id":"magicpaper-usage","response":{"subscription_type":"max","rate_limits_available":true,"rate_limits":{"five_hour":{"utilization":12.5,"resets_at":"2026-09-25T16:00:00+00:00"},"seven_day":{"utilization":21,"resets_at":"2026-09-30T16:00:00Z"},"model_scoped":[{"display_name":"Opus","utilization":34,"resets_at":"2026-09-30T16:00:00Z"}]}}}}
JSON
""")
            binary.setExecutable(true)
            val usage = ClaudePlanUsageQuery(ClaudeExecutable(binary.path), home.resolve("usage"), now = { 42 }).read()!!
            assertEquals("max", usage.plan)
            assertEquals(42, usage.observedAt)
            assertEquals(listOf("five_hour", "seven_day", "seven_day_model:opus"), usage.windows.map { it.id })
            assertEquals(listOf(.125f, .21f, .34f), usage.windows.map { it.usedFraction })
            assertEquals("Opus", usage.windows.last().scope)
            assertTrue(home.resolve("stdin").readText().contains("\"subtype\":\"get_usage\""))
            val arguments = home.resolve("args").readLines()
            assertTrue("--no-session-persistence" in arguments)
            assertEquals("", arguments[arguments.indexOf("--setting-sources") + 1])
        } finally { home.deleteRecursively() }
    }

    @Test fun unavailableLimitsDoNotBecomeAnEmptySuccessfulSnapshot() = runBlocking {
        if (windows) return@runBlocking
        val home = Files.createTempDirectory("claude-plan-unavailable").toFile()
        try {
            val binary = home.resolve("claude")
            binary.writeText("#!/bin/sh\ncat >/dev/null\ncat <<'JSON'\n" +
                """{"type":"control_response","response":{"subtype":"success","request_id":"magicpaper-usage","response":{"rate_limits_available":false,"rate_limits":null}}}
JSON
""")
            binary.setExecutable(true)
            assertNull(ClaudePlanUsageQuery(ClaudeExecutable(binary.path), home.resolve("usage")).read())
        } finally { home.deleteRecursively() }
    }
}
