package io.aequicor.magicpaper.data.checks

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.checks.*
import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.*

class CheckInputJournalTest {
    private val workspace = "/private/user/workspace"
    private val ref = CheckRef(CheckScope("project", "session", "request", 0), "call")
    private val command = CheckCommand(ref, workspace, listOf("tool", "private-payload"))
    private val receipt = CheckProcessReceipt("receipt", "group", 123)
    private val proof = CheckCompletionProof("receipt", "stopped", "restored", "attested")
    private val result = CheckResult("private-output", 0)

    @Test fun replayReconstructsUnknownAndNeverInterpretsSavedRelease() = runTest {
        val events = InMemoryEventJournal(); val payloads = InMemoryKeyValueStore()
        val first = CheckInputJournal(events, payloads, workspace)
        first.initialize()
        first.append(Input.Intent.Submit(command))
        first.append(Input.Fact.ProcessPrepared(ref, receipt))
        first.append(Input.Intent.Release(ref, receipt.id))
        val second = CheckInputJournal(events, payloads, workspace)
        second.initialize()
        assertEquals(CommandCheckMachine.Phase.UNKNOWN, second.state.checks.getValue(ref).phase)
        assertFailsWith<CheckRejected> { second.append(Input.Intent.Submit(command.copy(ref = ref.copy(callId = "new")))) }
        assertTrue(events.read(first.stream).all { record ->
            listOf(workspace, "private-payload", "private-output").none { it in record.detail }
        })
    }

    @Test fun exactLostAppendAcknowledgementIsAcceptedOnce() = runTest {
        val actual = InMemoryEventJournal(); var lose = true
        val events = object : EventJournal by actual {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val value = actual.append(expected, operation, at, detail)
                if (lose) { lose = false; throw IOException("lost acknowledgement") }
                return value
            }
        }
        val owner = CheckInputJournal(events, InMemoryKeyValueStore(), workspace)
        owner.initialize()
        val transition = owner.append(Input.Intent.Submit(command))
        assertIs<CommandCheckMachine.Effect.Prepare>(transition.effects.single())
        assertEquals(1, actual.read(owner.stream).size)
        assertFalse(owner.state.persistenceUnknown)
    }

    @Test fun changedPrefixCannotMasqueradeAsLostAcknowledgement() = runTest {
        val actual = InMemoryEventJournal()
        val events = object : EventJournal by actual {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                actual.append(expected.stream, "foreign", at)
                actual.append(expected.stream, operation, at, detail)
                throw IOException("lost acknowledgement")
            }
        }
        val owner = CheckInputJournal(events, InMemoryKeyValueStore(), workspace)
        owner.initialize()
        assertFailsWith<CheckOutcomeUnknown> { owner.append(Input.Intent.Submit(command)) }
        assertTrue(owner.state.persistenceUnknown)
    }

    @Test fun changedTimestampCannotMasqueradeAsExactCommit() = runTest {
        val actual = InMemoryEventJournal()
        val events = object : EventJournal by actual {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                actual.append(expected, operation, at + 1, detail)
                throw IOException("lost acknowledgement")
            }
        }
        val owner = CheckInputJournal(events, InMemoryKeyValueStore(), workspace)
        owner.initialize()
        assertFailsWith<CheckOutcomeUnknown> { owner.append(Input.Intent.Submit(command)) }
        assertTrue(owner.state.persistenceUnknown)
    }

    @Test fun corruptPayloadBlocksRestoreAndPreservesItsBytes() = runTest {
        val events = InMemoryEventJournal(); val payloads = InMemoryKeyValueStore()
        val owner = CheckInputJournal(events, payloads, workspace)
        owner.initialize(); owner.append(Input.Intent.Submit(command))
        val key = payloads.keys("check-input:").single()
        payloads.write(key, "corrupted")
        val reopened = CheckInputJournal(events, payloads, workspace)
        assertFailsWith<CheckOutcomeUnknown> { reopened.initialize() }
        assertEquals("corrupted", payloads.read(key))
        assertEquals(1, events.read(owner.stream).size)
    }

    @Test fun immutableCompletionRecoversLostTerminalWithoutNewProcessEffect() = runTest {
        val events = InMemoryEventJournal(); val payloads = InMemoryKeyValueStore()
        val owner = CheckInputJournal(events, payloads, workspace)
        owner.initialize(); owner.append(Input.Intent.Submit(command))
        owner.append(Input.Fact.ProcessPrepared(ref, receipt)); owner.append(Input.Intent.Release(ref, receipt.id))
        owner.saveCompletion(ref, proof, result)
        val reopened = CheckInputJournal(events, payloads, workspace)
        reopened.initialize()
        val recovered = checkNotNull(reopened.completion(ref))
        val transition = reopened.append(recovered)
        assertTrue(transition.effects.isEmpty())
        assertEquals(CommandCheckMachine.Phase.FINISHED, transition.state.checks.getValue(ref).phase)
        assertEquals(result, transition.state.checks.getValue(ref).result)
        assertFailsWith<IllegalStateException> { reopened.saveCompletion(ref, proof, result.copy(exitCode = 1)) }
    }

    @Test fun corruptCompletionCannotResolveUnknown() = runTest {
        val events = InMemoryEventJournal(); val payloads = InMemoryKeyValueStore()
        val owner = CheckInputJournal(events, payloads, workspace)
        owner.initialize(); owner.append(Input.Intent.Submit(command))
        owner.append(Input.Fact.ProcessPrepared(ref, receipt)); owner.saveCompletion(ref, proof, result)
        val key = payloads.keys("check-completion:").single()
        payloads.write(key, payloads.read(key)!!.replace("private-output", "different-output"))
        val reopened = CheckInputJournal(events, payloads, workspace)
        reopened.initialize()
        assertFailsWith<IllegalStateException> { reopened.completion(ref) }
        assertTrue(reopened.state.unknown)
    }

    @Test fun originalPayloadCancellationWinsOverReadbackFailure() = runTest {
        val actual = InMemoryKeyValueStore()
        val cancellation = CancellationException("original cancellation")
        var written = false
        val payloads = object : KeyValueStore by actual {
            override fun write(key: String, value: String) { written = true; throw cancellation }
            override fun read(key: String): String? = if (written) throw IOException("readback unavailable") else actual.read(key)
        }
        val owner = CheckInputJournal(InMemoryEventJournal(), payloads, workspace)
        owner.initialize()
        val caught = assertFailsWith<CancellationException> { owner.append(Input.Intent.Submit(command)) }
        // Coroutine stack recovery may wrap cancellation; the original cause and cleanup evidence survive.
        val chain = generateSequence<Throwable>(caught) { it.cause }.toList()
        assertTrue(chain.any { it === cancellation })
        assertIs<IOException>(cancellation.suppressedExceptions.single())
        assertTrue(owner.state.persistenceUnknown)
    }

    @Test fun cancellationBeforeTheAppendLeavesTheOwnerUsable() = runTest {
        val actual = InMemoryKeyValueStore(); val events = InMemoryEventJournal()
        lateinit var caller: Job
        var cancelOnWrite = true
        val payloads = object : KeyValueStore by actual {
            override fun write(key: String, value: String) { actual.write(key, value); if (cancelOnWrite) caller.cancel() }
        }
        val owner = CheckInputJournal(events, payloads, workspace)
        owner.initialize()
        caller = launch { owner.append(Input.Intent.Submit(command)) }
        caller.join()
        assertTrue(caller.isCancelled)
        // Nothing was appended, so a screen pause cannot make the whole workspace unavailable.
        assertFalse(owner.state.persistenceUnknown)
        assertTrue(events.read(owner.stream).isEmpty())
        cancelOnWrite = false
        assertIs<CommandCheckMachine.Effect.Prepare>(owner.append(Input.Intent.Submit(command)).effects.single())
        assertEquals(1, events.read(owner.stream).size)
    }

    @Test fun cancellationAfterAnExactCommitKeepsTheRecoveredState() = runTest {
        val actual = InMemoryEventJournal(); var cancel = true
        val events = object : EventJournal by actual {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val value = actual.append(expected, operation, at, detail)
                if (cancel) { cancel = false; throw CancellationException("cancelled after the record landed") }
                return value
            }
        }
        val owner = CheckInputJournal(events, InMemoryKeyValueStore(), workspace)
        owner.initialize()
        assertFailsWith<CancellationException> { owner.append(Input.Intent.Submit(command)) }
        assertFalse(owner.state.persistenceUnknown)
        assertEquals(CommandCheckMachine.Phase.PREPARING, owner.state.checks.getValue(ref).phase)
        owner.append(Input.Fact.ProcessPrepared(ref, receipt))
        assertEquals(2, actual.read(owner.stream).size)
    }

    @Test fun cancellationThatProvablyAppendedNothingLeavesTheOwnerUsable() = runTest {
        val actual = InMemoryEventJournal(); var cancel = true
        val events = object : EventJournal by actual {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (cancel) { cancel = false; throw CancellationException("cancelled before the record landed") }
                return actual.append(expected, operation, at, detail)
            }
        }
        val owner = CheckInputJournal(events, InMemoryKeyValueStore(), workspace)
        owner.initialize()
        assertFailsWith<CancellationException> { owner.append(Input.Intent.Submit(command)) }
        assertFalse(owner.state.persistenceUnknown)
        assertTrue(actual.read(owner.stream).isEmpty())
        assertIs<CommandCheckMachine.Effect.Prepare>(owner.append(Input.Intent.Submit(command)).effects.single())
    }

    @Test fun callerMutationDuringAcknowledgementCannotChangeDispatchedCommand() = runTest {
        val actual = InMemoryEventJournal(); val payloads = InMemoryKeyValueStore()
        val arguments = mutableListOf("tool", "original")
        val events = object : EventJournal by actual {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                arguments[1] = "changed-before-ack"
                return actual.append(expected, operation, at, detail)
            }
        }
        val owner = CheckInputJournal(events, payloads, workspace)
        owner.initialize()
        val transition = owner.append(Input.Intent.Submit(command.copy(arguments = arguments)))
        arguments[1] = "changed-after-ack"
        assertEquals(listOf("tool", "original"), (transition.effects.single() as CommandCheckMachine.Effect.Prepare).command.arguments)
        assertEquals(listOf("tool", "original"), owner.state.checks.getValue(ref).command.arguments)
        val reopened = CheckInputJournal(actual, payloads, workspace)
        reopened.initialize()
        assertEquals(listOf("tool", "original"), reopened.state.checks.getValue(ref).command.arguments)
    }
}
