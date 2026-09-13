package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.Skill
import io.aequicor.magicpaper.domain.SkillRepository
import kotlinx.serialization.json.Json

/** Хранилище установленных навыков поверх KeyValueStore. */
class JsonSkillRepository(
    private val store: KeyValueStore,
    private val json: Json,
) : SkillRepository {

    override suspend fun all(): List<Skill> {
        val raw = store.read(KEY_INDEX) ?: return emptyList()
        return runCatching { json.decodeFromString(ALL_SERIALIZER, raw) }
            .getOrDefault(emptyList())
            .sortedBy { it.name }
    }

    override suspend fun save(skill: Skill) {
        val current = all().associateBy { it.id }.toMutableMap()
        current[skill.id] = skill
        store.write(KEY_INDEX, json.encodeToString(ALL_SERIALIZER, current.values.toList()))
    }

    override suspend fun delete(id: String) {
        val rest = all().filterNot { it.id == id }
        store.write(KEY_INDEX, json.encodeToString(ALL_SERIALIZER, rest))
    }

    override suspend fun wipe() {
        store.delete(KEY_INDEX)
    }

    private companion object {
        const val KEY_INDEX = "skills"
        val ALL_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(Skill.serializer())
    }
}
