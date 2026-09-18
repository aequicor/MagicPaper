package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.storage.MediaStore
import io.aequicor.magicpaper.data.storage.StorageException
import io.aequicor.magicpaper.domain.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Binary media is embedded only for the existing portable JSON export, never in live history. */
internal suspend fun exportMediaAssets(sessions: List<ChatSession>, store: MediaStore): List<ExportedMediaAsset> =
    withContext(Dispatchers.Default) {
        sessions.flatMap { it.generatedMediaAssets() }.distinctBy { it.id }.map { asset ->
            ExportedMediaAsset(asset, Base64.encode(store.read(asset)))
        }
    }

/** Validate and install every referenced file before any imported conversation can reference it. */
internal suspend fun importMediaAssets(bundle: ProfileBundle, store: MediaStore) = withContext(Dispatchers.Default) {
    val referenced = bundle.sessions.flatMap { it.generatedMediaAssets() }.distinct()
    val entries = bundle.generatedAssets.groupBy { it.asset.id }
    if (entries.values.any { it.size != 1 }) throw StorageException("duplicate media archive identity", StorageException.Kind.CORRUPT)
    for (asset in referenced) {
        val entry = entries[asset.id]?.singleOrNull()
            ?: throw StorageException("missing media archive asset", StorageException.Kind.CORRUPT)
        if (!entry.asset.sameContent(asset) || asset.byteSize !in 1..MAX_ARCHIVE_ASSET_BYTES || entry.dataBase64.length > MAX_ARCHIVE_ASSET_BYTES * 4 / 3 + 4)
            throw StorageException("invalid media archive metadata", StorageException.Kind.CORRUPT)
        val bytes = try { Base64.decode(entry.dataBase64) }
            catch (failure: IllegalArgumentException) { throw StorageException("decode media archive", StorageException.Kind.CORRUPT, failure) }
        if (bytes.size.toLong() != asset.byteSize) throw StorageException("verify media archive size", StorageException.Kind.CORRUPT)
        val installed = store.put(bytes, asset.mimeType, asset.width, asset.height, asset.durationSeconds)
        if (!installed.sameContent(asset)) throw StorageException("verify media archive digest", StorageException.Kind.CORRUPT)
    }
}

private const val MAX_ARCHIVE_ASSET_BYTES = 256L * 1024 * 1024
private fun MediaAsset.sameContent(other: MediaAsset) = id == other.id && mimeType == other.mimeType && byteSize == other.byteSize
