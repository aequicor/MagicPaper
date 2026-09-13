package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.KSerializer

/** An entity draft outlives feature composition, while reset replaces its persistence generation. */
class PersistentDraftValue<T>(
    private val repository: DraftRepository,
    private val key: String,
    private val serializer: KSerializer<T>,
    private val initial: T,
    private val applicationScope: CoroutineScope,
) {
    private var scope: CoroutineScope? = null
    private var current: DraftSession<T>? = null
    private var epoch = -1L
    var paused: Boolean = false
        private set
    val draft: DraftSession<T>
        get() {
            if (current == null || epoch != repository.generation) {
                scope?.cancel()
                val next = CoroutineScope(applicationScope.coroutineContext + SupervisorJob(applicationScope.coroutineContext[Job]))
                scope = next
                current = DraftSession(repository, key, serializer, initial, next)
                epoch = repository.generation
            }
            return requireNotNull(current)
        }
    fun update(transform: (T) -> T) { if (!paused) draft.update(transform) }
    suspend fun flushDrafts() { current?.awaitSaved() }
    suspend fun prepareForReset() {
        paused = true
        flushDrafts()
        scope?.cancel(); scope = null; current = null
    }
    fun resumeAfterReset() { paused = false }
}
