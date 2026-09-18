package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.MediaAsset

/** Permanent media storage is independent of composer draft cleanup. The service owns record schemas. */
interface MediaStore {
    val available: Boolean
    suspend fun put(bytes: ByteArray, mimeType: String, width: Int = 0, height: Int = 0,
                    durationSeconds: Double? = null): MediaAsset
    suspend fun read(asset: MediaAsset): ByteArray
    suspend fun localPath(asset: MediaAsset): String?
    suspend fun fingerprint(value: String): String
    suspend fun readRecord(key: String): String?
    suspend fun writeRecord(key: String, value: String)
    suspend fun records(prefix: String): Map<String, String>
    suspend fun deleteRecord(key: String)
    suspend fun deleteAsset(asset: MediaAsset)
    suspend fun clear()
}

object UnavailableMediaStore : MediaStore {
    override val available = false
    private fun unavailable(): Nothing = throw StorageException("media", StorageException.Kind.UNAVAILABLE)
    override suspend fun put(bytes: ByteArray, mimeType: String, width: Int, height: Int, durationSeconds: Double?): MediaAsset = unavailable()
    override suspend fun read(asset: MediaAsset): ByteArray = unavailable()
    override suspend fun localPath(asset: MediaAsset): String? = null
    override suspend fun fingerprint(value: String): String = unavailable()
    override suspend fun readRecord(key: String): String? = null
    override suspend fun writeRecord(key: String, value: String): Unit = unavailable()
    override suspend fun records(prefix: String): Map<String, String> = emptyMap()
    override suspend fun deleteRecord(key: String): Unit = unavailable()
    override suspend fun deleteAsset(asset: MediaAsset): Unit = unavailable()
    override suspend fun clear() = Unit
}
