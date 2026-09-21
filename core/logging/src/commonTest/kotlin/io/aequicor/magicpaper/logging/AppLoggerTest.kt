package io.aequicor.magicpaper.logging

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class AppLoggerTest {
    @Test fun defaultLevelRecordsInfoAndErrorsWithoutDiagnosticPayloads() {
        val output = mutableListOf<AppLogEntry>()
        val log = AppLogger(sink = AppLogSink { output += it })
        var payloads = 0
        log.debug("runtime", "branch")
        log.trace("runtime", "payload") { payloads++; "private request" }
        log.info("runtime", "started")
        log.error("runtime", "failed", IllegalStateException("raw body"))
        assertEquals(defaultLogLevel(), log.level)
        assertFalse(log.isEnabled(LogLevel.DEBUG), "Detailed diagnostics are opt-in")
        assertFalse(log.isEnabled(LogLevel.TRACE), "Per-token stream logging stays an explicit opt-in")
        assertEquals(listOf(LogLevel.INFO, LogLevel.ERROR), output.map { it.level })
        assertEquals(0, payloads)
    }

    @Test fun refusedOperationIsRecordedWithoutAnException() {
        val output = mutableListOf<AppLogEntry>()
        val log = AppLogger(sink = AppLogSink { output += it })
        log.error("coding.tool", "call.failed", mapOf("tool" to "questionnaire", "category" to "ACTION",
            "failure" to "validation", "kind" to "application"))
        val entry = output.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals(emptyList(), entry.causeTypes, "A refusal has no local cause chain")
        assertEquals(mapOf("tool" to "questionnaire", "category" to "ACTION", "failure" to "validation", "kind" to "application"),
            entry.fields, "Tool identity and outcome are machine codes and remain readable")
    }

    @Test fun infoAndErrorRemainRecordedAtInfoAndHigherVerbosity() {
        val log = AppLogger(sink = AppLogSink {})
        for (level in LogLevel.entries.filter { it.ordinal >= LogLevel.INFO.ordinal }) {
            log.level = level
            log.info("runtime", "started")
            log.error("runtime", "failed", IllegalStateException("raw body"))
            assertTrue(log.isEnabled(LogLevel.INFO) && log.isEnabled(LogLevel.ERROR))
        }
    }

    @Test fun eachVerbosityIncludesEarlierLevelsAndTraceRemainsExplicit() {
        val log = AppLogger(sink = AppLogSink {})
        for (level in LogLevel.entries) {
            log.level = level
            LogLevel.entries.forEach { candidate -> assertEquals(candidate.ordinal <= level.ordinal, log.isEnabled(candidate)) }
        }
        log.trace("runtime", "trace_enabled") { "selected strategy" }
        assertEquals("selected strategy", log.history().single().detail)
        log.level = LogLevel.ERROR
        log.info("runtime", "not_recorded")
        assertEquals(1, log.history().size)
    }

    @Test fun normalEventsKeepOnlySafeMetadataAndOpaqueCorrelation() {
        val log = AppLogger(sink = AppLogSink {})
        val sensitiveId = "questionnaire:password is private"
        val fields = mapOf("requestId" to sensitiveId, "status" to "failed", "provider" to "openai",
            "generation" to "4", "model" to "sk-1234567890abcdef", "reason" to "user entered secret words",
            "message" to "secret message", "url" to "https://example.com/?token=secret", "apiKey" to "secret")
        log.info("drafts", "save_failed", fields)
        log.info("drafts", "retry", mapOf("requestId" to sensitiveId))
        val first = log.history().first()
        assertEquals(setOf("requestId", "status", "provider", "generation", "model", "reason"), first.fields.keys)
        assertEquals(first.fields["requestId"], log.history().last().fields["requestId"])
        assertTrue(first.fields.getValue("requestId").startsWith("id-"))
        assertEquals("failed", first.fields["status"])
        assertEquals("4", first.fields["generation"])
        listOf(sensitiveId, "sk-1234567890abcdef", "secret words", "secret message", "example.com").forEach {
            assertFalse(it in first.line(), it)
        }
    }

    /** Every failure logged without an exception passes the class of its cause this way, so it is part of the record. */
    @Test fun theClassOfACauseGivenAsAFieldIsKeptNotDropped() {
        val log = AppLogger(sink = AppLogSink {})
        log.error("checks", "output_unavailable", mapOf("causeType" to "IllegalStateException", "result" to "effects_blocked"))
        val entry = log.history().single()
        assertEquals("IllegalStateException", entry.fields["causeType"])
        assertEquals("effects_blocked", entry.fields["result"])
        assertTrue("\"causeType\":\"IllegalStateException\"" in entry.line(), entry.line())
    }

    @Test fun aCauseTypeThatIsNotAClassNameIsRefusedNotPublished() {
        val log = AppLogger(sink = AppLogSink {})
        log.error("checks", "output_unavailable", mapOf("causeType" to "the user typed secret words"))
        log.error("checks", "output_unavailable", mapOf("causeType" to "https://example.com/?token=secret"))
        log.history().forEach { assertEquals("[redacted]", it.fields["causeType"], it.line()) }
        assertFalse(log.history().any { "secret" in it.line() || "example.com" in it.line() })
    }

    @Test fun timingAndCounterMetricsKeepTheirNameWithoutListingEveryMetric() {
        val output = mutableListOf<AppLogEntry>()
        val log = AppLogger(sink = AppLogSink { output += it })
        log.info("coding.llm", "run.summary", mapOf("wallMs" to "919276", "firstResponseMs" to "508",
            "contextTokens" to "59969", "toolCalls" to "38", "requestCount" to "3", "modelSharePercent" to "64",
            "contextLimit" to "128000", "slowestCalls" to "claude thinks a lot"))
        val fields = output.single().fields
        assertEquals(setOf("wallMs", "firstResponseMs", "contextTokens", "toolCalls", "requestCount",
            "modelSharePercent", "contextLimit", "slowestCalls"), fields.keys,
            "A metric added by its owner must not silently disappear from the journal")
        assertEquals("919276", fields["wallMs"])
        assertEquals("[redacted]", fields["slowestCalls"],
            "A metric name carrying free text is refused, not published")
    }

    @Test fun exceptionTypesMessagesAndStacksRemainCorrelatedWithSecretsRedacted() {
        val original = IllegalArgumentException("api_key=private-value")
        val failure = IllegalStateException("https://example.com?access_token=private", original)
        val log = AppLogger(sink = AppLogSink {})
        log.error("settings", "save_failed", failure, mapOf("operationId" to "op-1", "recovery" to "retry"))
        val entry = log.history().single()
        assertEquals(listOf("IllegalStateException", "IllegalArgumentException"), entry.causeTypes)
        assertSame(original, failure.cause)
        assertFalse("private" in entry.line())
        assertFalse("example.com" in entry.line())
        assertEquals("retry", entry.fields["recovery"])
        assertEquals("[redacted URL]", entry.causeMessage, "The cause message stays available, sanitized")
        val stack = entry.causeStack!!
        assertTrue("IllegalStateException" in stack, "The stack keeps the failure type and frames: $stack")
        assertTrue(stack.lines().size > 1, "The stack must contain frames, not only the message")
        assertFalse('\n' in entry.line(), "One entry must remain one physical line")
    }

    @Test fun causeMessageAndStackAreBoundedAndSurviveBrokenRendering() {
        val log = AppLogger(sink = AppLogSink {})
        var cause: Throwable = IllegalStateException("secret".repeat(4_000))
        repeat(20) { cause = IllegalStateException("nested", cause) }
        log.error("runtime", "chain", cause)
        val entry = log.history().last()
        val chainStack = entry.causeStack!!
        assertTrue(entry.causeMessage!!.length <= 512)
        assertTrue(chainStack.length <= 8_192)
        assertTrue(chainStack.lines().size <= 41, "Header plus at most 40 frames")

        class BrokenRender(message: String) : IllegalStateException(message) {
            override fun toString(): String = error("broken renderer")
        }
        val broken = BrokenRender("password=private")
        log.error("runtime", "broken_cause", broken)
        val brokenEntry = log.history().last()
        assertEquals(listOf("BrokenRender"), brokenEntry.causeTypes)
        assertEquals("password=private", broken.message, "The exception itself is never modified")
        assertFalse("private" in brokenEntry.line())
    }

    @Test fun traceRedactsCredentialFieldsHeadersTokensAndKnownSecretAnswers() {
        val secrets = listOf("strange-value-123", "json-secret-123", "answer-value-123", "bearer-value-123",
            "cookie-value-123", "sk-secret123456789", "url-value-123")
        val log = AppLogger(initialLevel = LogLevel.TRACE, sink = AppLogSink {})
        log.trace("http", "response", knownSecrets = setOf(secrets.first())) {
            """{"password":"json-secret-123","answer":"answer-value-123","safe":"visible"}
Authorization: Bearer bearer-value-123
Cookie: session=cookie-value-123
strange-value-123 sk-secret123456789 https://example.com?token=url-value-123"""
        }
        val detail = log.history().single().detail!!
        secrets.forEach { assertFalse(it in detail, it) }
        assertTrue("visible" in detail)
        assertTrue("redacted" in detail)
        assertFalse('\n' in log.history().single().line(), "One entry must remain one physical line")
    }

    @Test fun payloadCauseChainAndRetentionAreBounded() {
        val log = AppLogger(initialLevel = LogLevel.TRACE, sink = AppLogSink {}, retention = 3, payloadLimit = 64)
        repeat(8) { index -> log.trace("runtime", "detail", mapOf("count" to "$index")) { "safe ".repeat(50_000) } }
        val entries = log.history()
        assertEquals(3, entries.size)
        assertEquals(listOf("5", "6", "7"), entries.map { it.fields["count"] })
        assertTrue(entries.all { it.detail!!.length <= 64 && it.detail.endsWith("[truncated]") })
        var cause: Throwable = IllegalStateException("secret")
        repeat(20) { cause = IllegalStateException("secret", cause) }
        log.error("runtime", "chain", cause)
        assertEquals(6, log.history().last().causeTypes.size)
    }

    @Test fun failingSinkUsesFallbackWithoutReplacingTheOriginalFailure() {
        val fallback = mutableListOf<AppLogEntry>()
        val log = AppLogger(sink = AppLogSink { throw IllegalArgumentException("token=private-sink-secret") },
            fallbackSink = AppLogSink { fallback += it })
        val original = IllegalStateException("token=private-original-secret")
        log.error("drafts", "persist_failed", original)
        assertEquals("sink_failed", fallback.single().event)
        assertEquals(listOf("IllegalArgumentException"), fallback.single().causeTypes)
        assertEquals(listOf("persist_failed", "sink_failed"), log.history().map { it.event })
        assertFalse(log.history().any { "private" in it.line() })
        assertEquals("token=private-original-secret", original.message)
    }

    @Test fun doubleSinkFailureRemainsDiscoverableInBoundedMemory() {
        val fail = AppLogSink { throw IllegalStateException("password=private") }
        val log = AppLogger(sink = fail, fallbackSink = fail)
        log.info("runtime", "started")
        assertEquals(listOf("started", "sink_failed", "fallback_sink_failed"), log.history().map { it.event })
        assertFalse(log.history().any { "private" in it.line() })
    }

    @Test fun failureWhileConstructingTraceIsOwnedByDiagnostics() {
        val log = AppLogger(initialLevel = LogLevel.TRACE, sink = AppLogSink {})
        log.trace("http", "request") { error("password=private") }
        val entry = log.history().single()
        assertEquals("trace_payload_failed", entry.event)
        assertEquals("request", entry.fields["operation"])
        assertEquals(LogLevel.ERROR, entry.level)
        assertFalse("private" in entry.line())
    }

    @Test fun traceConstructionPropagatesCancellation() {
        val log = AppLogger(initialLevel = LogLevel.TRACE, sink = AppLogSink {})
        val cancellation = CancellationException("cancelled")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            log.trace("http", "request") { throw cancellation }
        })
        assertTrue(log.history().isEmpty())
    }

    @Test fun oversizedKnownSecretsCannotEscapeThroughTheScanBoundary() {
        val secret = "opaque-" + "a".repeat(70_000)
        val log = AppLogger(initialLevel = LogLevel.TRACE, sink = AppLogSink {})
        log.trace("http", "response", knownSecrets = setOf(secret)) { "prefix $secret" }
        assertEquals("[redacted oversized secret]", log.history().single().detail)
    }

    @Test fun excessKnownSecretsRefuseDetailInsteadOfLeavingUnexaminedCredentials() {
        val secrets = (0..128).map { "opaque-value-$it" }.toSet()
        val log = AppLogger(initialLevel = LogLevel.TRACE, sink = AppLogSink {})
        log.trace("http", "response", knownSecrets = secrets) { secrets.last() }
        assertEquals("[redacted secret set exceeds limit]", log.history().single().detail)
    }

    @Test fun sharedFacadeInitializesAndRedactsQuotedCredentialsOnEveryPlatform() {
        val previous = AppLog.level
        try {
            AppLog.level = LogLevel.TRACE
            AppLog.trace("logging_test", "facade_ready") {
                """{"apiKey":"private-api-value","answer":"private-answer-value","url":"https://private.example"}"""
            }
            val entry = AppLog.history().last()
            assertEquals("facade_ready", entry.event)
            assertEquals(LogLevel.TRACE, entry.level)
            assertFalse("private" in entry.line())
            assertTrue("redacted" in entry.detail.orEmpty())
        } finally { AppLog.level = previous }
    }

    @Test fun failedEntryPreparationProducesSafeDiagnosticsWithoutInterruptingTheOwner() {
        val output = mutableListOf<AppLogEntry>()
        val log = AppLogger(sink = AppLogSink { output += it })
        val brokenFields = object : Map<String, String> by emptyMap() {
            override val entries: Set<Map.Entry<String, String>>
                get() = error("password=private-metadata")
        }
        val original = IllegalArgumentException("password=private-original")
        log.error("drafts", "persist_failed", original, brokenFields)
        val entry = output.single()
        assertEquals("entry_preparation_failed", entry.event)
        assertEquals(listOf("IllegalStateException"), entry.causeTypes)
        assertFalse("private" in entry.line())
        assertEquals("password=private-original", original.message)
        log.info("drafts", "next_command")
        assertEquals("next_command", output.last().event)
    }
}
