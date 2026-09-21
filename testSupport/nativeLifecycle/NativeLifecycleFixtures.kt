package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.backend.lifecycle.NativeLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit test storage. Production composition always supplies the real EventJournal adapter. */
class MemoryNativeJournal : NativeLifecycleJournal {
    private val lock = Mutex()
    private var snapshot = NativeJournalSnapshot(NativeJournalRevision("test", 0), emptyList(), emptyList())
    override suspend fun snapshot() = lock.withLock { snapshot }
    override suspend fun append(expected: NativeJournalRevision, entry: NativeJournalEntry) = lock.withLock {
        if (snapshot.revision != expected) return@withLock null
        snapshot = NativeJournalSnapshot(expected.copy(position = expected.position + 1), snapshot.entries + entry, snapshot.positions + (expected.position + 1))
        snapshot.revision
    }
}
fun testNativeLifecycle(journal: NativeLifecycleJournal = MemoryNativeJournal()) =
    NativeLifecycleOwner(journal, NativeDiagnostics { _, _, _, _ -> })

fun <T> nativeRunBlocking(block: suspend CoroutineScope.() -> T): T = runBlocking {
    val owner = testNativeLifecycle()
    val run = NativeRunRef("session", "fixture")
    owner.begin(run, null)
    withContext(NativeAttemptContext(run, owner), block)
}
