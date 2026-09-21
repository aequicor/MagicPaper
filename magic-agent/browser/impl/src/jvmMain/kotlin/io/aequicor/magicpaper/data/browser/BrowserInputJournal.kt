package io.aequicor.magicpaper.data.browser

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.browser.BrowserMachine
import io.aequicor.magicpaper.domain.tools.ToolStateRejection
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID

/** Called under the session's operation lock. Replayed inputs never execute their derived effects. */
internal class BrowserInputJournal(private val journal: EventJournal, private val owner: BrowserMachine.Owner) {
    @Serializable private data class Entry(val id: String, val owner: BrowserMachine.Owner,
        val resetEpoch: Long, val input: BrowserMachine.Input)
    private val json = Json { encodeDefaults = true }
    internal val stream = "browser:" + listOf(owner.sessionId, owner.requestId).joinToString(":") {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
    }
    @Volatile var state = BrowserMachine.initial()
        private set
    private var snapshot: JournalSnapshot? = null

    init { require(owner.sessionId.isNotBlank() && owner.requestId.isNotBlank()) }

    suspend fun initialize() {
        if (state.persistenceUnknown) throw BrowserJournalUnknown(IllegalStateException("Browser journal requires recovery"))
        if (snapshot != null) return
        try {
            val restored = journal.snapshot(stream)
            state = replay(restored)
            snapshot = restored
            if (restored.records.isEmpty()) dispatch(BrowserMachine.Intent.Start(owner))
            else {
                // Handles, tabs and dispatch authority do not survive a process/owner lifetime.
                // The same request cannot be silently reopened as a fresh browser run.
                dispatch(BrowserMachine.Fact.Restored)
            }
        } catch (failure: Exception) {
            unknown(failure, "restore.failed")
            if (failure is CancellationException) throw failure
            throw BrowserJournalUnknown(failure)
        }
    }

    suspend fun dispatch(input: BrowserMachine.Input): BrowserMachine.Transition {
        val before = checkNotNull(snapshot) { "Browser journal has not been restored" }
        val next = BrowserMachine.reduce(state, input)
        next.effects.filterIsInstance<BrowserMachine.Effect.Reject>().firstOrNull()?.let { throw ToolStateRejection(it.reason) }
        val detail = json.encodeToString(Entry(UUID.randomUUID().toString(), owner, before.revision.resetEpoch, input))
        val observed = try {
            val appended = journal.append(before.revision, OPERATION, System.currentTimeMillis(), detail)
                ?: error("Browser journal revision changed")
            check(appended.stream == stream && appended.seq > before.revision.seq && appended.operation == OPERATION && appended.detail == detail) {
                "Browser journal acknowledgement does not match its input"
            }
            JournalSnapshot(before.revision.copy(seq = appended.seq), before.records + appended)
        } catch (failure: Exception) {
            val recovered = try {
                withContext(NonCancellable) {
                    journal.snapshot(stream).also {
                        replay(it)
                        check(it.revision.resetEpoch == before.revision.resetEpoch &&
                            it.records.size == before.records.size + 1 && it.records.dropLast(1) == before.records &&
                            it.records.last().seq > before.revision.seq && it.records.last().detail == detail) {
                            "Browser journal append outcome is not confirmed"
                        }
                    }
                }
            } catch (readFailure: Exception) {
                failure.addSuppressed(readFailure)
                unknown(failure, "append.unknown")
                if (failure is CancellationException) throw failure
                throw BrowserJournalUnknown(failure)
            }
            snapshot = recovered
            state = next.state
            // A cancelled caller never starts an external action, even after a confirmed intent write.
            if (failure is CancellationException) throw failure
            recovered
        }
        snapshot = observed
        state = next.state
        return next
    }

    fun persistenceUnknown(failure: Throwable) = unknown(failure, "outcome.unknown")

    private fun unknown(failure: Throwable, event: String) {
        state = BrowserMachine.reduce(state, BrowserMachine.Fact.PersistenceUnknown).state
        AppLog.error("browser", event, mapOf("sessionId" to owner.sessionId, "requestId" to owner.requestId,
            "causeType" to failure.javaClass.simpleName, "result" to "effects_blocked"))
    }

    private fun replay(observed: JournalSnapshot): BrowserMachine.State {
        check(observed.revision.stream == stream && observed.revision.seq >= 0 && observed.revision.resetEpoch >= 0)
        check(observed.records.isEmpty() || observed.records.last().seq == observed.revision.seq)
        var result = BrowserMachine.initial()
        var sequence = 0L
        val ids = mutableSetOf<String>()
        for (record in observed.records) {
            check(record.stream == stream && record.seq > sequence && record.seq <= observed.revision.seq && record.operation == OPERATION)
            val entry = json.decodeFromString<Entry>(record.detail)
            check(entry.id.isNotBlank() && ids.add(entry.id) && entry.owner == owner && entry.resetEpoch == observed.revision.resetEpoch)
            val transition = BrowserMachine.reduce(result, entry.input)
            check(transition.effects.none { it is BrowserMachine.Effect.Reject })
            check(transition.state.owner == owner)
            result = transition.state
            sequence = record.seq
        }
        return result
    }

    private companion object { const val OPERATION = "browser.input.v1" }
}

internal class BrowserJournalUnknown(cause: Throwable) : IllegalStateException(
    "Не удалось подтвердить сохранение браузерного действия. Запуск остановлен; автоматический повтор отключён.", cause)
