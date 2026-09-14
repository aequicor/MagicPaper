package io.aequicor.magicpaper.data.storage

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Codex app-server alone writes/refreshes auth.json. This reference never creates a token copy. */
class CodexAuthSecretStore(private val delegate: SecretStore? = null, private val authFile: File) : SecretStore {
    override suspend fun read(reference: String): String? {
        if (reference != SecretReferences.CODEX_AUTH) return checkNotNull(delegate).read(reference)
        return withContext(Dispatchers.IO) {
            try { if (authFile.exists()) authFile.readText() else null }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { throw StorageException("read Codex auth", StorageException.Kind.READ) }
        }
    }
    override suspend fun write(reference: String, value: String) {
        require(reference != SecretReferences.CODEX_AUTH) { "Codex app-server owns authentication writes" }
        checkNotNull(delegate).write(reference, value)
    }
    override suspend fun delete(reference: String) {
        require(reference != SecretReferences.CODEX_AUTH) { "Use Codex app-server logout" }
        checkNotNull(delegate).delete(reference)
    }
}
