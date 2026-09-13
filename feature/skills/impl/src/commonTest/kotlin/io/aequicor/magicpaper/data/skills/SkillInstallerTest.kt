package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.CatalogEntry
import io.aequicor.magicpaper.domain.Skill
import io.aequicor.magicpaper.domain.SkillDraft
import io.aequicor.magicpaper.domain.SkillInstaller
import io.aequicor.magicpaper.domain.SkillSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillInstallerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store = SkillStore(JsonSkillRepository(InMemoryKeyValueStore(), json))
    private val installer = SkillInstaller(store)

    private val entry = CatalogEntry(
        id = "summarize",
        name = "Резюме текста",
        description = "Когда просят кратко пересказать.",
        instructions = "Сожми текст до 3–5 пунктов.",
        tags = listOf("пересказ"),
    )

    @Test
    fun installsCatalogEntry() = runTest {
        val outcome = installer.installFromCatalog(entry)
        val installed = (outcome as SkillInstaller.Outcome.Installed).skill
        assertTrue(outcome is SkillInstaller.Outcome.Installed)
        assertEquals(SkillSource.CATALOG, installed.source)
        assertTrue(installed.enabled)
        assertEquals(1, store.all().size)
    }

    @Test
    fun reinstallUpdatesKeepingId() = runTest {
        val first = (installer.installFromCatalog(entry) as SkillInstaller.Outcome.Installed).skill
        val again = installer.installFromCatalog(entry.copy(description = "обновлено"))
        val updated = (again as SkillInstaller.Outcome.Installed).skill
        assertEquals(first.id, updated.id)
        assertEquals("обновлено", updated.description)
        assertEquals(1, store.all().size)
    }

    @Test
    fun conflictBlocksCrossSourceName() = runTest {
        installer.installFromCatalog(entry)
        val draft = SkillDraft(
            name = "резюме текста",
            description = "самодельный",
            instructions = "по-своему",
        )
        val outcome = installer.installDraft(draft)
        assertTrue(outcome is SkillInstaller.Outcome.Conflict)
        assertEquals(1, store.all().size)
    }

    @Test
    fun draftInstallsAsSelfMade() = runTest {
        val draft = SkillDraft(
            name = "Мой приём",
            description = "Когда нужна магия.",
            instructions = "Делай шаг за шагом.",
        )
        val outcome = installer.installDraft(draft)
        val installed = (outcome as SkillInstaller.Outcome.Installed).skill
        assertEquals(SkillSource.SELF_MADE, installed.source)
        assertTrue(installed.createdAt > 0)
    }

    @Test
    fun blankNameIsRejected() = runTest {
        val outcome = installer.installDraft(SkillDraft("", "d", "i"))
        assertTrue(outcome is SkillInstaller.Outcome.Conflict)
        assertTrue(store.all().isEmpty())
    }
}

class SkillStoreTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun storeReflectsRepoAndExposesFlow() = runTest {
        val store = SkillStore(JsonSkillRepository(InMemoryKeyValueStore(), json))
        assertTrue(store.skills.value.isEmpty())
        val skill = Skill(
            id = "s1",
            name = "Тест",
            description = "Когда тестируем.",
            instructions = "Проверь всё.",
        )
        store.save(skill)
        assertEquals(1, store.skills.value.size)
        assertEquals(1, store.relevantFor("что угодно").size)
        store.delete("s1")
        assertTrue(store.skills.value.isEmpty())
    }
}
