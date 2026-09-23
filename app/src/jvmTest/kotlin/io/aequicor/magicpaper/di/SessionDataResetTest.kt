package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionDataResetTest {
    private class CountingMedia(private val delegate: MediaStore = UnavailableMediaStore) : MediaStore by delegate {
        var clears = 0
        override suspend fun clear() { clears++ }
    }

    // A sessions reset keeps configuration with the journal it replays from and erases everything else, execution
    // journals included: one left behind while its projects are gone is exactly a journal that no longer replays.
    @Test fun keepsConfigurationAndErasesSessionsWithEveryExecutionJournal() = runTest {
        val persistence = persistenceStores(InMemoryDurableByteStore())
        val store = InMemoryKeyValueStore()
        val media = CountingMedia()
        val kept = listOf("settings-configuration", "plugin-preferences", "skill-library", "usage-ledger")
        val erased = listOf("coding-workflow:70", "chat-workflow:63", "command-check:ab", "task-worktree:cA:cw",
            "organism-workflow:6f", "native-lifecycle.v1/ab", "plan-1", "provider-tools:63", "questionnaire:61",
            "request-pins-journal:{}", "browser:cw:cw", "computer-authority:application", "media-generation:6d")
        (kept + erased).forEach { persistence.events.append(it, "input", 0) }
        val keptKeys = listOf("settings", "llm_profiles", "model-dossiers", "settings-input-1", "coding-model-catalog",
            "plugins", "plugin-input-1", "skills", "skill-input-1", "usage:archive:v1", "usage_archive_v1", "usage-input-1",
            "settings-credential-cleanup", "profiles-credential-cleanup", "settings-owner-credential-cleanup")
        val erasedKeys = listOf("coding-projects", "coding-sessions", "coding-log:p:s", "coding-orchestration-s", "coding-input:70:31",
            "chats", "chat:1", "chat-input:63:31", "coding-plans", "coding-plan-v2-1", "session-organism-1", "organism-input:6f:31",
            "coding-unread-markers", "coding-interaction-decisions", "request-pins:{}", "check-input:ab:1", "check-call:ab",
            "worktree-input:cA", "agent-tools-1", "provider-tool-output-1", "tool-questionnaires")
        (keptKeys + erasedKeys).forEach { store.write(it, "{}") }
        val keptDrafts = listOf("settings:overview", "settings:profile:p", "experience:result", "skills:library:filter", "plugin:notes")
        val erasedDrafts = listOf("chat:1", "coding:s", "coding-session-create:p", "questionnaire:[]", "planning:p:1:form",
            "coding-form/\"p\"/\"s\"/x", "skills:project:p:a")
        (keptDrafts + erasedDrafts).forEach { assertTrue(persistence.drafts.save(DraftRecord(it, 1, "{}"))) }

        clearSessionData(persistence, store, media)

        assertEquals(kept.sorted(), persistence.events.streams().sorted())
        assertEquals(keptKeys.sorted(), store.keys("").sorted())
        assertEquals(keptDrafts.sorted(), persistence.drafts.keys("").sorted())
        assertEquals(1, media.clears, "generated media belongs to the erased chats")
    }
}
