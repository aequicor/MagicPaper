package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.data.storage.MediaStore
import io.aequicor.magicpaper.data.storage.PersistenceStores
import io.aequicor.magicpaper.logging.AppLog

/**
 * What "Сбросить сессии" keeps: application settings, provider profiles and model descriptions with their
 * secrets, the skill library, plugin preferences and the usage ledger — each with the journal it replays from.
 * Everything else is erased: projects, coding sessions and chats, their plans, drafts and generated media, and
 * every journal of their execution (checks, worktrees, native lifecycle, tools, questionnaires, organisms, pins).
 *
 * The list names what stays rather than what goes: an execution journal left behind while the data it refers
 * to is gone is exactly a journal that no longer replays. A new kind of configuration must be added here on
 * purpose; anything unnamed is session data.
 */
internal object SessionResetKeeps {
    val streams = setOf("settings-configuration", "plugin-preferences", "skill-library", "usage-ledger")
    private val keys = setOf("settings", "llm_profiles", "model-dossiers", "coding-model-catalog", "plugins", "skills",
        "usage:archive:v1", "settings-credential-cleanup", "profiles-credential-cleanup", "settings-owner-credential-cleanup")
    private val keyPrefixes = listOf("settings-input-", "plugin-input-", "skill-input-", "usage-input-")
    private val draftPrefixes = listOf("settings:", "experience:", "skills:library:", "skills:review:", "plugin:")

    // A desktop store also lists files it has no manifest entry for, by their file name: every character other than
    // a letter, a digit, '-' or '.' becomes '_' (FileKeyValueStore). A kept key must survive under that name too.
    private fun fileName(key: String) = key.map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '_' }.joinToString("")
    private val keyFileNames = keys.map(::fileName).toSet()

    fun key(name: String) = name in keys || name in keyFileNames || keyPrefixes.any(name::startsWith)
    fun draft(name: String) = draftPrefixes.any(name::startsWith)
}

/**
 * Runs inside the application reset, after every owner has paused and before any resumes. Secrets are kept
 * whole: a removed draft releases its own secret references and attachment blobs through the draft owner.
 */
internal suspend fun clearSessionData(persistence: PersistenceStores, store: KeyValueStore, media: MediaStore) {
    val streams = persistence.events.streams().filter { it !in SessionResetKeeps.streams }
    streams.forEach { persistence.events.drop(it) }
    val drafts = persistence.drafts.keys("").filterNot(SessionResetKeeps::draft)
    drafts.forEach { persistence.drafts.remove(it) }
    val keys = store.keys("").filterNot(SessionResetKeeps::key)
    keys.forEach(store::delete)
    // Generated media belongs to the erased chats; its connection checks are a cache and are repeated on demand.
    media.clear()
    AppLog.info("SettingsService", "sessions_reset", mapOf("streams" to streams.size.toString(),
        "drafts" to drafts.size.toString(), "keys" to keys.size.toString()))
}
