package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An opaque concurrency token, bound to one stream and one application reset generation. */
data class JournalRevision(val stream: String, val seq: Long, val resetEpoch: Long = 0)

/**
 * Records and their concurrency token are read under the same backend lock. An incomplete durable
 * reset is an error, never old records relabelled with a new epoch or a successful empty snapshot.
 */
data class JournalSnapshot(val revision: JournalRevision, val records: List<JournalRecord>)

/**
 * One recorded fact.
 *
 * [seq] is assigned by the journal, is monotonic across every stream, and is never reused.
 * It orders records that share a millisecond and records written by different owners, which
 * [at] cannot do: a wall clock can repeat, stand still or move backwards.
 *
 * [operation] and [detail] are opaque here. The vocabulary belongs to whoever writes it — the
 * planner's own operations are a closed enumeration on its side — because a journal that knew
 * the words would have to be changed every time a feature learned a new one.
 */
data class JournalRecord(
    val seq: Long,
    val at: Long,
    val stream: String,
    val operation: String,
    val detail: String = "",
)

/**
 * An append-only record of what the application did, kept beside the state it acted on.
 *
 * It exists because state alone cannot answer one question: an effect that was requested and
 * whose outcome was never recorded must not be repeated, and after a crash the only evidence
 * of the request is that it was written down before the effect ran. State that is rewritten
 * in place — a plan document saved whole on every change — loses that the moment the write
 * it was part of is lost or rolled back.
 *
 * So nothing here modifies a record. A stream is dropped whole when its owner is gone; that
 * is the only way anything leaves.
 */
interface EventJournal {
    /**
     * Appends one record and returns it with its assigned sequence number.
     *
     * The sequence is reserved before the record is written. A crash in between leaves a gap,
     * never a record written over: losing a fact nobody finished recording is recoverable,
     * and silently replacing one that was recorded is not.
     */
    suspend fun append(stream: String, operation: String, at: Long, detail: String = ""): JournalRecord

    /** Null means another writer changed/deleted the stream or reset the application; nothing was appended. */
    suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String = ""): JournalRecord?

    suspend fun snapshot(stream: String): JournalSnapshot

    /** Nonempty streams, including records whose owner checkpoint was never saved. */
    suspend fun streams(): List<String>

    /**
     * Every nonempty stream's snapshot, read together instead of one [snapshot] call per name
     * in [streams]. A store replaying its whole journal at startup calls [streams] to discover
     * its owners and then [snapshot] on each one; a backend whose [snapshot] re-scans all of
     * storage to serve one stream (see `DurableEventJournal`) would otherwise pay for that scan
     * once per owner instead of once total. The default keeps today's per-stream behavior for
     * every journal that does not override it.
     */
    suspend fun snapshotAll(): Map<String, JournalSnapshot> = streams().associateWith { snapshot(it) }

    /** Every record of one stream, in the order it was appended. */
    suspend fun read(stream: String): List<JournalRecord>

    /** Drops a stream whose owner is gone. The sequence does not rewind. */
    suspend fun drop(stream: String)

    /** Delete only the observed generation. A dropped stream retains an opaque revision fence. */
    suspend fun drop(expected: JournalRevision): Boolean
}

/** Test/preview journal; production factories never substitute it for an unavailable backend. */
class InMemoryEventJournal : EventJournal {
    private val mutex = Mutex()
    private val records = mutableListOf<JournalRecord>()
    private val dropped = mutableMapOf<String, Long>()
    private var seq = 0L

    override suspend fun append(stream: String, operation: String, at: Long, detail: String): JournalRecord = mutex.withLock {
        appendLocked(stream, operation, at, detail)
    }

    override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? = mutex.withLock {
        if (revision(expected.stream) != expected) null else appendLocked(expected.stream, operation, at, detail)
    }

    private fun appendLocked(stream: String, operation: String, at: Long, detail: String): JournalRecord {
        require(stream.isNotBlank()) { "Записи журнала принадлежат потоку" }
        seq += 1
        return JournalRecord(seq, at, stream, operation, detail).also { records += it }
    }

    private fun revision(stream: String) = JournalRevision(stream,
        maxOf(records.lastOrNull { it.stream == stream }?.seq ?: 0, dropped[stream] ?: 0))

    override suspend fun snapshot(stream: String): JournalSnapshot = mutex.withLock {
        JournalSnapshot(revision(stream), records.filter { it.stream == stream })
    }

    override suspend fun streams(): List<String> = mutex.withLock { records.map { it.stream }.distinct().sorted() }

    override suspend fun read(stream: String): List<JournalRecord> = snapshot(stream).records

    override suspend fun drop(stream: String) { mutex.withLock { dropLocked(stream) } }

    override suspend fun drop(expected: JournalRevision): Boolean = mutex.withLock {
        if (revision(expected.stream) != expected) false else { dropLocked(expected.stream); true }
    }

    private fun dropLocked(stream: String) {
        require(stream.isNotBlank())
        dropped[stream] = ++seq
        records.removeAll { it.stream == stream }
    }
}
