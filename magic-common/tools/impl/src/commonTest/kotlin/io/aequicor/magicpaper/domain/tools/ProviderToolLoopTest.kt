package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderToolLoopTest {
    private val profile = LlmProfile("profile", "Profile", baseUrl = "https://example.invalid", modelId = "model")
    private val messages = listOf(LlmMessage(LlmChatRole.USER, "private prompt text"))
    private val args = buildJsonObject { put("value", "private argument") }
    private val definition = ToolDefinition("effect", "Effect", buildJsonObject {
        put("type", "object"); putJsonObject("properties") { putJsonObject("value") { put("type", "string") } }
        putJsonArray("required") { add("value") }; put("additionalProperties", false)
    }, mutating = true)
    private fun turn(id: String = "call", arguments: JsonObject = args) = LlmToolTurn(
        calls = listOf(LlmToolCall(id, definition.wireName, arguments)), provider = profile.provider)
    private fun gateway(block: suspend (List<LlmToolExchange>) -> LlmToolTurn) = object : LlmGateway {
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("No text fallback")
        override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
            exchanges: List<LlmToolExchange>): LlmToolTurn {
            assertEquals(listOf(definition.wireName), tools.map { it.name })
            return block(exchanges)
        }
    }
    private fun session(receipts: ToolReceiptStore = MemoryToolReceiptStore(), definition: ToolDefinition = this.definition,
        action: suspend () -> JsonElement): ToolSession {
        val registry = ToolRegistry(listOf(JsonToolCommand(definition) { _, _, _ -> action() }))
        return DefaultToolSession(ToolExecutionContext("chat", "session", "session", "request", ToolRole.CHAT,
            CodingInteractionMode.RESEARCH), registry, ToolExecutor(registry, receipts))
    }
    private fun testLoop(gateway: LlmGateway, journal: EventJournal,
        outputs: ProviderToolOutputs = StoredProviderToolOutputs(InMemoryKeyValueStore())) = DefaultProviderToolLoop(gateway, journal, outputs)
    private suspend fun EventJournal.entries() = streams().flatMap { read(it) }

    @Test fun executesOnlyAfterDurableIntentAndFeedsResultsIntoTheNextModelTurn() = runTest {
        val journal = InMemoryEventJournal()
        var effects = 0
        val tools = session {
            assertContains(journal.entries().last().detail, "ExecuteTool")
            effects++
            JsonPrimitive("private result")
        }
        val seen = mutableListOf<List<LlmToolExchange>>()
        val loop = testLoop(gateway { exchanges ->
            assertContains(journal.entries().last().detail, "RequestModel")
            seen += exchanges.toList()
            if (exchanges.isEmpty()) turn() else {
                assertEquals(JsonPrimitive("private result"), exchanges.single().results.single().content)
                LlmToolTurn(text = "final", provider = profile.provider)
            }
        }, journal)
        assertEquals("final", loop.run("run", profile, messages, tools))
        assertEquals(1, effects)
        assertEquals(2, seen.size)
        assertEquals(ProviderToolMachine.Phase.SUCCEEDED, loop.restore("run").phase)
        val stored = journal.entries().joinToString { it.detail }
        for (payload in listOf("private prompt text", "private argument", "private result", "final")) assertFalse(stored.contains(payload))
        assertEquals("final", loop.run("run", profile, messages, tools))
        assertEquals(1, effects)
        assertEquals(2, seen.size)
    }

    @Test fun repeatedCallReusesDurableReceiptWhileChangedArgumentsNeverExecute() = runTest {
        for (change in listOf(false, true)) {
            var effects = 0
            val journal = InMemoryEventJournal()
            val tools = session { effects++; JsonPrimitive("result") }
            val loop = testLoop(gateway { exchanges -> when (exchanges.size) {
                0 -> turn()
                1 -> turn(arguments = if (change) buildJsonObject { put("value", "changed") } else args)
                else -> LlmToolTurn(text = "done")
            } }, journal)
            if (change) assertFailsWith<IllegalStateException> { loop.run("run", profile, messages, tools) }
            else assertEquals("done", loop.run("run", profile, messages, tools))
            assertEquals(1, effects)
        }
    }

    @Test fun rejectedArgumentsAreReturnedAsErrorsAndCanBeCorrectedWithANewId() = runTest {
        var effects = 0
        val tools = session { effects++; JsonPrimitive("ok") }
        val loop = testLoop(gateway { exchanges -> when (exchanges.size) {
            0 -> turn(arguments = JsonObject(emptyMap()))
            1 -> { assertTrue(exchanges.single().results.single().isError); turn("corrected") }
            else -> LlmToolTurn(text = "done")
        } }, InMemoryEventJournal())
        assertEquals("done", loop.run("run", profile, messages, tools))
        assertEquals(1, effects)
    }

    @Test fun unknownMutationStopsContinuationAndRestartDoesNotRepeatIt() = runTest {
        val journal = InMemoryEventJournal()
        var calls = 0; var effects = 0
        val model = gateway { calls++; turn() }
        val tools = session { effects++; error("connection lost after submission") }
        assertFailsWith<IllegalStateException> { testLoop(model, journal).run("run", profile, messages, tools) }
        val restored = testLoop(model, journal)
        assertEquals(ProviderToolMachine.Phase.UNKNOWN, restored.restore("run").phase)
        assertFailsWith<IllegalStateException> { restored.run("run", profile, messages, tools) }
        assertEquals(1, calls); assertEquals(1, effects)
        assertEquals(ToolPhase.UNKNOWN, tools.receipt("call")?.phase)
    }

    @Test fun cancellationDuringMutationIsUnknownAndNeverConvertedToSuccess() = runTest {
        val journal = InMemoryEventJournal()
        var effects = 0
        val loop = testLoop(gateway { turn() }, journal)
        val tools = session { effects++; awaitCancellation() }
        val run = launch { loop.run("run", profile, messages, tools) }
        runCurrent(); run.cancelAndJoin()
        assertEquals(1, effects)
        assertEquals(ProviderToolMachine.Phase.UNKNOWN, loop.restore("run").phase)
        assertEquals(ToolPhase.UNKNOWN, tools.receipt("call")?.phase)
    }

    @Test fun appendWriteThenThrowReadbackPermitsOnlyTheAlreadyPersistedAttempt() = runTest {
        val backing = InMemoryEventJournal()
        var thrown = false
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val result = backing.append(expected, operation, at, detail)
                if (!thrown && detail.contains("RequestModel")) { thrown = true; error("lost ack") }
                return result
            }
        }
        var calls = 0
        val loop = testLoop(gateway { calls++; LlmToolTurn(text = "done") }, journal)
        assertEquals("done", loop.run("run", profile, messages, session { error("unused") }))
        assertEquals(1, calls)
    }

    @Test fun unprovablePersistencePreventsProviderCallAndReplaysAsUnknown() = runTest {
        val backing = InMemoryEventJournal()
        var unreadable = false
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val result = backing.append(expected, operation, at, detail)
                if (detail.contains("RequestModel")) { unreadable = true; error("lost ack") }
                return result
            }
            override suspend fun snapshot(stream: String): JournalSnapshot {
                if (unreadable) error("read unavailable")
                return backing.snapshot(stream)
            }
        }
        var calls = 0
        val provider = gateway { calls++; LlmToolTurn(text = "done") }
        assertFailsWith<IllegalStateException> {
            testLoop(provider, journal).run("run", profile, messages, session { error("unused") })
        }
        assertEquals(0, calls)
        assertEquals(ProviderToolMachine.Phase.UNKNOWN, testLoop(provider, backing).restore("run").phase)
    }

    @Test fun turnLimitStopsProviderWithoutRepeatingToolEffects() = runTest {
        var calls = 0; var effects = 0
        val journal = InMemoryEventJournal()
        val loop = testLoop(gateway { calls++; turn() }, journal)
        assertFailsWith<IllegalStateException> { loop.run("run", profile, messages, session { effects++; JsonPrimitive("ok") }, maxTurns = 1) }
        assertEquals(1, calls); assertEquals(1, effects)
        assertEquals(ProviderToolMachine.Phase.FAILED, loop.restore("run").phase)
    }

    @Test fun questionnaireSecretAnswersLeaveOnlyRedactedReceiptsAndEvents() = runTest {
        val def = ToolCatalog.get("questionnaire")
        val events = mutableListOf<ToolEvent>()
        val secretArgs = buildJsonObject { putJsonArray("questions") { add(buildJsonObject {
            put("id", "q"); put("title", "Credential"); put("secret", true)
        }) } }
        val tools = session(definition = def) { buildJsonArray { add(buildJsonObject { put("questionId", "q"); put("text", "private credential") }) } }
        tools.events.observe { events += it }
        assertContains(tools.call("q", def.wireName, secretArgs).toString(), "private credential")
        assertFalse(tools.receipt("q").toString().contains("private credential"))
        assertFalse(events.toString().contains("private credential"))
        assertEquals(ToolPhase.SUCCEEDED, tools.receipt("q")?.phase)
    }
    @Test fun finalArtifactRecoversCrashBeforeSuccessFactWithoutProviderOrToolReplay() = runTest {
        val backing = InMemoryEventJournal()
        val outputs = StoredProviderToolOutputs(InMemoryKeyValueStore())
        val interrupted = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (detail.contains("OutputStored")) error("stopped before completion record")
                return backing.append(expected, operation, at, detail)
            }
        }
        var calls = 0
        val provider = gateway { calls++; LlmToolTurn(text = "Durable answer") }
        val tools = session { error("unused") }
        assertFailsWith<IllegalStateException> { testLoop(provider, interrupted, outputs).run("run", profile, messages, tools) }
        val restored = testLoop(provider, backing, outputs)
        val records = backing.entries().size
        assertEquals(ProviderToolMachine.Phase.SUCCEEDED, restored.restore("run").phase)
        val recovered = assertIs<ProviderToolRecovery.Completed>(restored.inspect("run"))
        assertEquals("Durable answer", recovered.output.text)
        assertEquals("run", recovered.output.ref.runId)
        assertEquals(records, backing.entries().size, "Inspection replay never writes facts or effects")
        assertEquals("Durable answer", restored.run("run", profile, messages, tools))
        assertEquals(1, calls)
        assertFalse(backing.entries().joinToString { it.detail }.contains("Durable answer"))
    }

    @Test fun completedArtifactCanBeHandedToChatAgainAfterItsOwnerRestarts() = runTest {
        val journal = InMemoryEventJournal()
        val outputs = StoredProviderToolOutputs(InMemoryKeyValueStore())
        var calls = 0
        val provider = gateway { calls++; LlmToolTurn(text = "Durable answer") }
        val tools = session { error("unused") }
        assertEquals("Durable answer", testLoop(provider, journal, outputs).run("run", profile, messages, tools))
        assertEquals("Durable answer", testLoop(provider, journal, outputs).run("run", profile, messages, tools))
        assertEquals(1, calls)
    }

    @Test fun outputWriteThenThrowIsResolvedByExactImmutableReadback() = runTest {
        val journal = InMemoryEventJournal()
        val backing = StoredProviderToolOutputs(InMemoryKeyValueStore())
        val outputs = object : ProviderToolOutputs by backing {
            override suspend fun save(output: ProviderToolOutput) { backing.save(output); error("ack lost") }
        }
        var calls = 0
        val provider = gateway { calls++; LlmToolTurn(text = "Committed answer") }
        assertEquals("Committed answer", testLoop(provider, journal, outputs).run("run", profile, messages, session { error("unused") }))
        assertEquals(1, calls)
    }

    @Test fun missingCorruptAndWrongAttemptArtifactsCannotProveCompletion() = runTest {
        for (fault in 0..2) {
            val journal = InMemoryEventJournal()
            val backing = StoredProviderToolOutputs(InMemoryKeyValueStore())
            var corrupt = false
            val outputs = object : ProviderToolOutputs by backing {
                override suspend fun get(runId: String, attemptId: String): ProviderToolOutput? {
                    val value = backing.get(runId, attemptId) ?: return null
                    if (!corrupt) return value
                    return when (fault) { 0 -> null; 1 -> value.copy(text = "tampered"); else -> value.copy(ref = value.ref.copy(attempt = "other")) }
                }
            }
            var calls = 0
            val provider = gateway { calls++; LlmToolTurn(text = "Answer") }
            val tools = session { error("unused") }
            testLoop(provider, journal, outputs).run("run", profile, messages, tools)
            corrupt = true
            val restored = testLoop(provider, journal, outputs)
            assertEquals(ProviderToolMachine.Phase.UNKNOWN, restored.restore("run").phase)
            assertEquals(ProviderToolRecovery.Unknown, restored.inspect("run"))
            assertFailsWith<IllegalStateException> { restored.run("run", profile, messages, tools) }
            assertEquals(1, calls)
        }
    }

    @Test fun cancellationIsPreservedWhenRecordingItsToolOutcomeFails() = runTest {
        val backing = InMemoryEventJournal()
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (detail.contains("ToolReturned")) error("journal write failed")
                return backing.append(expected, operation, at, detail)
            }
        }
        var completion: Throwable? = null
        var observed: Throwable? = null
        val loop = testLoop(gateway { turn() }, journal)
        val task = launch {
            try { loop.run("run", profile, messages, session { awaitCancellation() }) }
            catch (failure: Throwable) { observed = failure; throw failure }
        }
        task.invokeOnCompletion { completion = it }
        runCurrent()
        task.cancel(CancellationException("explicit stop")); task.join()
        val cancelled = assertIs<CancellationException>(completion)
        assertContains(cancelled.message.orEmpty(), "explicit stop")
        val thrown = assertIs<CancellationException>(observed)
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it.suppressedExceptions.isNotEmpty() })
    }


    @Test fun inspectionNeedsOnlyTheRunIdentityAndNeverCreatesAnotherAttempt() = runTest {
        val backing = InMemoryEventJournal()
        val outputs = StoredProviderToolOutputs(InMemoryKeyValueStore())
        var calls = 0
        val provider = gateway { calls++; LlmToolTurn(text = "Saved response") }
        testLoop(provider, backing, outputs).run("run", profile, messages, session { error("unused") })
        val readOnly = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? =
                error("Inspection cannot write")
        }
        val restored = testLoop(gateway { error("Inspection cannot call a provider") }, readOnly, outputs)
        val completed = assertIs<ProviderToolRecovery.Completed>(restored.inspect("run"))
        assertEquals("Saved response", completed.output.text)
        assertTrue(completed.output.ref.identity.isNotBlank())
        assertEquals(ProviderToolRecovery.Missing, restored.inspect("another-run"))
        assertEquals(1, calls)
    }

    @Test fun inspectionDistinguishesMissingInterruptedAndUnknownWithoutWrites() = runTest {
        val backing = InMemoryEventJournal()
        val loop = testLoop(gateway { error("not used") }, backing)
        assertEquals(ProviderToolRecovery.Missing, loop.inspect("run"))
        val stream = "provider-tools:72756e"
        fun entry(id: String, input: ProviderToolMachine.Input) = buildJsonObject {
            put("id", id); put("input", Json.encodeToJsonElement(ProviderToolMachine.Input.serializer(), input)); put("resetEpoch", 0)
        }.toString()
        backing.append(stream, "provider-tools.input.v1", 1, entry("start", ProviderToolMachine.Intent.Start("run", "identity", emptySet())))
        val before = backing.entries().size
        assertEquals(ProviderToolRecovery.Interrupted, loop.inspect("run"))
        assertEquals(before, backing.entries().size)
        backing.append(stream, "provider-tools.input.v1", 2, entry("request", ProviderToolMachine.Intent.RequestModel("attempt")))
        assertEquals(ProviderToolRecovery.Unknown, loop.inspect("run"))
        assertEquals(before + 1, backing.entries().size)
    }

    @Test fun inspectionRejectsMisroutedCorruptAndReorderedJournalProof() = runTest {
        val backing = InMemoryEventJournal()
        val outputs = StoredProviderToolOutputs(InMemoryKeyValueStore())
        testLoop(gateway { LlmToolTurn(text = "Saved response") }, backing, outputs)
            .run("run", profile, messages, session { error("unused") })
        val original = backing.snapshot(backing.streams().single())
        val first = original.records.first()
        val firstEntry = Json.parseToJsonElement(first.detail).jsonObject
        val changedStart = JsonObject(firstEntry + ("input" to JsonObject(firstEntry.getValue("input").jsonObject + ("runId" to JsonPrimitive("other")))))
        val corruptions = listOf(
            original.copy(revision = original.revision.copy(stream = "other")),
            original.copy(records = original.records.mapIndexed { index, record -> if (index == 0) record.copy(stream = "other") else record }),
            original.copy(records = original.records.reversed()),
            original.copy(records = original.records.mapIndexed { index, record -> if (index == 1) record.copy(seq = first.seq) else record }),
            original.copy(revision = original.revision.copy(seq = original.revision.seq + 1)),
            original.copy(revision = original.revision.copy(resetEpoch = original.revision.resetEpoch + 1)),
            original.copy(records = listOf(first.copy(detail = changedStart.toString())) + original.records.drop(1)),
            original.copy(records = original.records.mapIndexed { index, record -> if (index == 1) record.copy(detail = JsonObject(
                Json.parseToJsonElement(record.detail).jsonObject + ("id" to firstEntry.getValue("id"))).toString()) else record }),
        )
        for (snapshot in corruptions) {
            val journal = object : EventJournal by backing {
                override suspend fun snapshot(stream: String): JournalSnapshot = snapshot
                override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? = error("No write")
            }
            assertEquals(ProviderToolRecovery.Unknown, testLoop(gateway { error("No provider") }, journal, outputs).inspect("run"))
        }
    }

    @Test fun globalJournalGapsAreValidAndLostAcknowledgementCannotCrossStreams() = runTest {
        val backing = InMemoryEventJournal()
        var calls = 0
        val gaps = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                backing.append("unrelated", "another-owner", at)
                return backing.append(expected, operation, at, detail)
            }
        }
        val outputs = StoredProviderToolOutputs(InMemoryKeyValueStore())
        testLoop(gateway { calls++; LlmToolTurn(text = "Saved response") }, gaps, outputs)
            .run("run", profile, messages, session { error("unused") })
        assertIs<ProviderToolRecovery.Completed>(testLoop(gateway { error("No replay") }, gaps, outputs).inspect("run"))
        assertEquals(1, calls)

        var submitted = false
        val misrouted = object : EventJournal by InMemoryEventJournal() {
            private val journal = InMemoryEventJournal()
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val record = journal.append(expected, operation, at, detail)
                submitted = true
                error("Acknowledgement lost")
            }
            override suspend fun snapshot(stream: String): JournalSnapshot = journal.snapshot(stream).let {
                if (submitted) it.copy(revision = it.revision.copy(stream = "other")) else it
            }
        }
        assertFailsWith<IllegalStateException> { testLoop(gateway { error("Must not submit") }, misrouted)
            .run("run", profile, messages, session { error("unused") }) }
    }

    @Test fun inspectionPreservesStorageCancellation() = runTest {
        val journal = object : EventJournal by InMemoryEventJournal() {
            override suspend fun snapshot(stream: String): JournalSnapshot = throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> { testLoop(gateway { error("unused") }, journal).inspect("run") }
    }
}
