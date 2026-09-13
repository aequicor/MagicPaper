package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.UsageArchive
import io.aequicor.magicpaper.domain.UsageRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class JsonUsageRepository(private val store: KeyValueStore, private val json: Json) : UsageRepository {
    override fun load(): UsageArchive = store.read("usage:archive:v1")?.let { json.decodeFromString<UsageArchive>(it) } ?: UsageArchive()
    override fun save(archive: UsageArchive) = store.write("usage:archive:v1", json.encodeToString(archive))
}
