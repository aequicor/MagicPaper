package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.CatalogEntry
import io.aequicor.magicpaper.domain.Skill
import io.aequicor.magicpaper.domain.SkillDraft
import io.aequicor.magicpaper.domain.SkillInstallOutcome
import io.aequicor.magicpaper.domain.SkillInstaller
import io.aequicor.magicpaper.domain.SkillSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillInstallerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store = SkillStore(InMemoryKeyValueStore(), io.aequicor.magicpaper.data.storage.InMemoryEventJournal(), json, kotlinx.coroutines.Dispatchers.Unconfined)
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
        store.start()
        val outcome = installer.installFromCatalog(entry, checkNotNull(store.catalog.value.installBasis(entry.name)))
        val installed = (outcome as SkillInstallOutcome.Installed).skill
        assertTrue(outcome is SkillInstallOutcome.Installed)
        assertEquals(SkillSource.CATALOG, installed.source)
        assertTrue(installed.enabled)
        assertEquals(1, store.all().size)
    }

    @Test
    fun reinstallUpdatesKeepingId() = runTest {
        store.start()
        val first = (installer.installFromCatalog(entry, checkNotNull(store.catalog.value.installBasis(entry.name))) as SkillInstallOutcome.Installed).skill
        val again = installer.installFromCatalog(entry.copy(description = "обновлено"), checkNotNull(store.catalog.value.installBasis(entry.name)))
        val updated = (again as SkillInstallOutcome.Installed).skill
        assertEquals(first.id, updated.id)
        assertEquals("обновлено", updated.description)
        assertEquals(1, store.all().size)
    }

    @Test
    fun conflictBlocksCrossSourceName() = runTest {
        store.start()
        installer.installFromCatalog(entry, checkNotNull(store.catalog.value.installBasis(entry.name)))
        val draft = SkillDraft(
            name = "резюме текста",
            description = "самодельный",
            instructions = "по-своему",
        )
        store.start()
        val outcome = installer.installDraft(draft, checkNotNull(store.catalog.value.installBasis(draft.name)))
        assertTrue(outcome is SkillInstallOutcome.Conflict)
        assertEquals(1, store.all().size)
    }

    @Test
    fun draftInstallsAsSelfMade() = runTest {
        val draft = SkillDraft(
            name = "Мой приём",
            description = "Когда нужна магия.",
            instructions = "Делай шаг за шагом.",
        )
        store.start()
        val outcome = installer.installDraft(draft, checkNotNull(store.catalog.value.installBasis(draft.name)))
        val installed = (outcome as SkillInstallOutcome.Installed).skill
        assertEquals(SkillSource.SELF_MADE, installed.source)
        assertTrue(installed.createdAt > 0)
    }

    @Test
    fun blankNameIsRejected() = runTest {
        store.start()
        val outcome = installer.installDraft(SkillDraft("", "d", "i"), checkNotNull(store.catalog.value.installBasis("")))
        assertTrue(outcome is SkillInstallOutcome.Conflict)
        assertTrue(store.all().isEmpty())
    }
}

class SkillStoreTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun storeReflectsRepoAndExposesFlow() = runTest {
        val store = SkillStore(InMemoryKeyValueStore(), io.aequicor.magicpaper.data.storage.InMemoryEventJournal(), json, kotlinx.coroutines.Dispatchers.Unconfined)
        assertTrue(store.catalog.value.items.isEmpty())
        val skill = Skill(
            id = "s1",
            name = "Тест",
            description = "Когда тестируем.",
            instructions = "Проверь всё.",
        )
        store.start()
        store.install(skill, checkNotNull(store.catalog.value.installBasis(skill.name)))
        assertEquals(1, store.catalog.value.items.size)
        assertEquals(1, store.relevantFor("что угодно").size)
        store.delete(store.catalog.value.items.single().ref)
        assertTrue(store.catalog.value.items.isEmpty())
    }
}
