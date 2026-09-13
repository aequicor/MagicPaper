package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.CatalogEntry
import io.aequicor.magicpaper.domain.Skill
import io.aequicor.magicpaper.domain.SkillSelector
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillSelectorTest {

    private val selector = SkillSelector()
    private val catalog = EmbeddedSkillCatalog()

    private suspend fun installedSkills(): List<Skill> =
        catalog.entries().mapIndexed { i, entry ->
            Skill(
                id = "s$i",
                name = entry.name,
                description = entry.description,
                instructions = entry.instructions,
                tags = entry.tags,
            )
        }

    @Test
    fun findsSummarizeForParaphraseRequest() = runTest {
        val hits = selector.select("перескажи этот текст кратко", installedSkills())
        assertEquals("Резюме текста", hits.first().name)
    }

    @Test
    fun findsProofreadForCorrectionRequest() = runTest {
        val hits = selector.select("проверь текст и исправь ошибки", installedSkills())
        assertEquals("Вычитка текста", hits.first().name)
    }

    @Test
    fun disabledSkillsAreSkipped() = runTest {
        val skills = installedSkills().map {
            if (it.name == "Резюме текста") it.copy(enabled = false) else it
        }
        val hits = selector.select("перескажи этот текст кратко", skills)
        assertTrue(hits.none { it.name == "Резюме текста" })
    }

    @Test
    fun emptyQuerySelectsNothing() = runTest {
        assertTrue(selector.select("   ", installedSkills()).isEmpty())
    }
}

class JsonSkillRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store = io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore()
    private val repo = JsonSkillRepository(store, json)

    private val skill = Skill(
        id = "a",
        name = "Буква",
        description = "Когда буквы.",
        instructions = "Пиши буквы.",
        tags = listOf("алфавит"),
    )

    @Test
    fun roundTrip() = runTest {
        repo.save(skill)
        assertEquals(listOf(skill), repo.all())
    }

    @Test
    fun deleteAndWipe() = runTest {
        repo.save(skill)
        repo.delete("a")
        assertTrue(repo.all().isEmpty())
        repo.save(skill)
        repo.wipe()
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun brokenDataFallsBackToEmpty() = runTest {
        store.write("skills", "{broken")
        assertTrue(repo.all().isEmpty())
    }
}
