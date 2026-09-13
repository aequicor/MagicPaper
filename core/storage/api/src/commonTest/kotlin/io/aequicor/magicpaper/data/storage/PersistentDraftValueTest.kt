package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import kotlinx.serialization.builtins.serializer
import kotlin.test.*

class PersistentDraftValueTest {
    @Test fun editsSurviveCompositionAndApplicationLifetimeAndResetDiscardsOldWriter() = runTest {
        class Store : DraftRepository {
            var delegate = InMemoryDraftRepository()
            override var generation = 0L
            override suspend fun load(key: String) = delegate.load(key)
            override suspend fun revision(key: String) = delegate.revision(key)
            override suspend fun save(draft: DraftRecord) = if (draft.generation == generation) delegate.save(draft) else false
            override suspend fun delete(key: String, revision: Long) = delegate.delete(key, revision)
            fun reset() { generation++; delegate = InMemoryDraftRepository() }
        }
        val repository = Store()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = PersistentDraftValue(repository, "plugin:notes", String.serializer(), "", scope)
        first.update { "Сохранённый текст" }; first.flushDrafts(); scope.cancel()
        val restored = PersistentDraftValue(repository, "plugin:notes", String.serializer(), "", backgroundScope)
        restored.draft.awaitSaved()
        assertEquals("Сохранённый текст", restored.draft.state.value.value)
        val stale = restored.draft
        restored.prepareForReset()
        repository.reset()
        stale.update("Старая запись")
        restored.resumeAfterReset()
        restored.draft.awaitSaved()
        assertEquals("", restored.draft.state.value.value)
        restored.update { "Новый текст" }; restored.flushDrafts()
        assertEquals("\"Новый текст\"", repository.load("plugin:notes")?.payload)
    }
}
