package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ComputerJournalTest {
    private suspend fun enabled(computer: DesktopComputerUse): ComputerLease {
        computer.configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL)
        return checkNotNull(computer.begin("session", "request"))
    }
    private suspend fun EventJournal.entries() = streams().flatMap { read(it) }

    @Test fun committedIntentPrecedesInputAndJournalContainsNoTypedTextOrScreenshots(): Unit = runBlocking {
        val journal = InMemoryEventJournal()
        val desktop = FakeComputerDesktop()
        val computer = testComputer(desktop, journal = journal)
        try {
            val lease = enabled(computer)
            val screenshot = computer.execute("session", lease, request("screenshot"), "observe")
            desktop.onPerform = {
                val record = runBlocking { journal.entries().last() }
                assertContains(record.detail, "Execute")
                assertContains(record.detail, "input-call")
            }
            assertFalse(computer.execute("session", lease, request("type") {
                put("screenshot_id", screenshot.screenshotId()); put("text", "private typed text")
            }, "input-call").failed())
            val recorded = journal.entries().joinToString { it.detail }
            assertFalse(recorded.contains("private typed text"))
            assertFalse(recorded.contains("image/png"))
            assertFalse(recorded.contains("data:image"))
            assertEquals(1, desktop.performed.size)
        } finally { computer.close() }
    }

    @Test fun lostAppendAcknowledgementReadsBackExactIntentBeforeOneInput(): Unit = runBlocking {
        val backing = InMemoryEventJournal()
        val threw = AtomicBoolean()
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val record = backing.append(expected, operation, at, detail)
                if (detail.contains("Execute") && detail.contains("input-call") && threw.compareAndSet(false, true)) error("Lost acknowledgement")
                return record
            }
        }
        val desktop = FakeComputerDesktop()
        val computer = testComputer(desktop, journal = journal)
        try {
            val lease = enabled(computer)
            val screenshot = computer.execute("session", lease, request("screenshot"), "observe")
            val args = request("click") { put("screenshot_id", screenshot.screenshotId()); put("x", 20); put("y", 20) }
            assertFalse(computer.execute("session", lease, args, "input-call").failed())
            assertTrue(computer.execute("session", lease, args, "input-call").failed())
            assertEquals(1, desktop.performed.size)
        } finally { computer.close() }
    }

    @Test fun revocationDoesNotWaitForDiskAndSuppressesAnAlreadyQueuedInput(): Unit = runBlocking {
        val backing = InMemoryEventJournal()
        val pending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (detail.contains("Execute") && detail.contains("input-call")) {
                    pending.complete(Unit)
                    release.await()
                }
                return backing.append(expected, operation, at, detail)
            }
        }
        val desktop = FakeComputerDesktop()
        val computer = testComputer(desktop, journal = journal)
        try {
            val lease = enabled(computer)
            val screenshot = computer.execute("session", lease, request("screenshot"), "observe")
            val call = async { computer.execute("session", lease, request("click") {
                put("screenshot_id", screenshot.screenshotId()); put("x", 20); put("y", 20)
            }, "input-call") }
            withTimeout(5000) { pending.await() }
            computer.disable("session")
            assertNull(computer.grant("session"))
            assertEquals(ComputerAccess.OFF, computer.state.value.access)
            release.complete(Unit)
            assertTrue(call.await().failed())
            assertTrue(desktop.performed.isEmpty())
        } finally { release.complete(Unit); computer.close() }
    }

    @Test fun uncertainPersistenceBlocksInputAndFreshOwnerNeverRestoresOldRequestAuthority(): Unit = runBlocking {
        val backing = InMemoryEventJournal()
        val unreadable = AtomicBoolean()
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val record = backing.append(expected, operation, at, detail)
                if (detail.contains("Execute") && detail.contains("input-call")) { unreadable.set(true); error("Lost acknowledgement") }
                return record
            }
            override suspend fun snapshot(stream: String): JournalSnapshot {
                check(!unreadable.get()) { "Read unavailable" }
                return backing.snapshot(stream)
            }
        }
        val desktop = FakeComputerDesktop()
        val computer = testComputer(desktop, journal = journal)
        try {
            val lease = enabled(computer)
            val screenshot = computer.execute("session", lease, request("screenshot"), "observe")
            assertTrue(computer.execute("session", lease, request("click") {
                put("screenshot_id", screenshot.screenshotId()); put("x", 20); put("y", 20)
            }, "input-call").failed())
            assertTrue(desktop.performed.isEmpty())
            assertNull(computer.grant("session"))
        } finally { computer.close() }
        val restored = testComputer(desktop, journal = backing)
        try {
            assertNull(restored.begin("session", "request"))
            assertNull(restored.grant("session"))
            assertTrue(desktop.performed.isEmpty())
            // The fixture starts with an explicitly disabled policy; a fresh request
            // needs the application's confirmed policy, never the replayed old grant.
            restored.configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL)
            assertNotNull(restored.begin("session", "explicit-new-request"))
        } finally { restored.close() }
    }

    @Test fun actionFollowedByFailedCaptureIsUnknownAndItsCallIdCannotRepeatAfterObservation(): Unit = runBlocking {
        val journal = InMemoryEventJournal()
        val desktop = FakeComputerDesktop()
        val computer = testComputer(desktop, journal = journal)
        try {
            val lease = enabled(computer)
            val screenshot = computer.execute("session", lease, request("screenshot"), "observe")
            desktop.onCapture = { error("Capture interrupted after input") }
            assertTrue(computer.execute("session", lease, request("click") {
                put("screenshot_id", screenshot.screenshotId()); put("x", 20); put("y", 20)
            }, "input-call").failed())
            assertEquals(1, desktop.performed.size)
            assertTrue(journal.entries().any { it.detail.contains("\"unknown\":true") })
            desktop.onCapture = {}
            val fresh = computer.execute("session", lease, request("screenshot"), "fresh-observation")
            assertTrue(computer.execute("session", lease, request("click") {
                put("screenshot_id", fresh.screenshotId()); put("x", 20); put("y", 20)
            }, "input-call").failed())
            assertEquals(1, desktop.performed.size)
        } finally { computer.close() }
    }

    @Test fun resetJoinsOldWriterAndNewIncarnationCannotInheritAnOldEndpointLease(): Unit = runBlocking {
        val journal = InMemoryEventJournal()
        val computer = testComputer(FakeComputerDesktop(), journal = journal)
        try {
            val before = enabled(computer)
            val endpoint = computer.endpoint(before, "request")
            computer.prepareForReset()
            assertNull(computer.grant("session"))
            journal.streams().forEach { journal.drop(it) }
            computer.resumeAfterReset()
            computer.configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL)
            val after = checkNotNull(computer.begin("session", "request"))
            assertNotEquals(before, after)
            endpoint.close()
            assertEquals(after, computer.grant("session"))
            assertTrue(computer.execute("session", before, request("screenshot"), "old-call").failed())
        } finally { computer.close() }
    }

    @Test fun lostAcknowledgementCannotReplaceThePreviouslyVerifiedHistory(): Unit = runBlocking {
        val backing = InMemoryEventJournal()
        var corrupt = false
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val record = backing.append(expected, operation, at, detail)
                if (detail.contains("Execute") && detail.contains("input-call")) { corrupt = true; error("Lost acknowledgement") }
                return record
            }
            override suspend fun snapshot(stream: String): JournalSnapshot = backing.snapshot(stream).let { snapshot ->
                if (!corrupt) snapshot else snapshot.copy(records = snapshot.records.mapIndexed { index, record ->
                    if (index != 0) record else record.copy(detail = JsonObject(Json.parseToJsonElement(record.detail).jsonObject +
                        ("id" to JsonPrimitive("replacement-history"))).toString())
                })
            }
        }
        val desktop = FakeComputerDesktop()
        val computer = testComputer(desktop, journal = journal)
        try {
            val lease = enabled(computer)
            val screenshot = computer.execute("session", lease, request("screenshot"), "observe")
            assertTrue(computer.execute("session", lease, request("click") {
                put("screenshot_id", screenshot.screenshotId()); put("x", 20); put("y", 20)
            }, "input-call").failed())
            assertTrue(desktop.performed.isEmpty())
            assertNull(computer.grant("session"))
            assertTrue(computer.state.value.error)
        } finally { computer.close() }
    }

    @Test fun corruptedEpochOrderingDuplicateEnvelopeAndHighWaterAllFailClosed(): Unit = runBlocking {
        val backing = InMemoryEventJournal()
        val seed = testComputer(FakeComputerDesktop(), journal = backing)
        val original = try { enabled(seed); backing.snapshot(backing.streams().single()) } finally { seed.close() }
        val first = original.records.first()
        val firstId = Json.parseToJsonElement(first.detail).jsonObject.getValue("id")
        val invalid = listOf(
            original.copy(revision = original.revision.copy(resetEpoch = original.revision.resetEpoch + 1)),
            original.copy(records = original.records.reversed()),
            original.copy(records = original.records.mapIndexed { index, record -> if (index == 1) record.copy(seq = first.seq) else record }),
            original.copy(records = original.records.mapIndexed { index, record -> if (index == 1) record.copy(detail =
                JsonObject(Json.parseToJsonElement(record.detail).jsonObject + ("id" to firstId)).toString()) else record }),
            original.copy(revision = original.revision.copy(seq = original.revision.seq + 1)),
            JournalSnapshot(original.revision.copy(seq = -1), emptyList()),
            JournalSnapshot(original.revision.copy(seq = 0, resetEpoch = -1), emptyList()),
        )
        for (snapshot in invalid) {
            val journal = object : EventJournal by backing {
                override suspend fun snapshot(stream: String) = snapshot
                override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? = error("Cannot append corrupt history")
            }
            val desktop = FakeComputerDesktop()
            val computer = testComputer(desktop, journal = journal)
            try {
                assertFailsWith<ComputerAuthorityUnavailable> { computer.begin("session", "next-request") }
                assertNull(computer.grant("session"))
                assertTrue(computer.state.value.error)
                assertTrue(desktop.performed.isEmpty())
                assertEquals(0, desktop.captures)
            } finally { computer.close() }
        }
    }

    @Test fun corruptJournalNeverProducesAGrantOrAnyNativeOperation(): Unit = runBlocking {
        val backing = InMemoryEventJournal()
        val journal = object : EventJournal by backing {
            override suspend fun snapshot(stream: String): JournalSnapshot = JournalSnapshot(JournalRevision("another-owner", 0), emptyList())
        }
        val desktop = FakeComputerDesktop()
        val computer = testComputer(desktop, journal = journal)
        try {
            assertFailsWith<ComputerAuthorityUnavailable> { enabled(computer) }
            assertNull(computer.grant("session"))
            assertTrue(computer.state.value.error)
            assertEquals(0, desktop.captures)
            assertTrue(desktop.performed.isEmpty())
        } finally { computer.close() }
    }
}
