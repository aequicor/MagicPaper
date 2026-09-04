package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.Skill
import io.aequicor.magicpaper.domain.SkillLibrary
import io.aequicor.magicpaper.domain.SkillRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Единый наблюдаемый слой навыков: для агента — порт [SkillLibrary],
 * для плагинов — репозиторий + StateFlow, чтобы «Лавка» и «Самообучение»
 * видели изменения друг друга без ручных обновлений.
 */
class SkillStore(private val repo: SkillRepository) : SkillRepository, SkillLibrary {

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    override suspend fun all(): List<Skill> = refresh()

    override suspend fun save(skill: Skill) {
        repo.save(skill)
        refresh()
    }

    override suspend fun delete(id: String) {
        repo.delete(id)
        refresh()
    }

    override suspend fun wipe() {
        repo.wipe()
        refresh()
    }

    /** Агент получает актуальный список; отбор делает селектор. */
    override suspend fun relevantFor(query: String, limit: Int): List<Skill> = refresh()

    private suspend fun refresh(): List<Skill> {
        val list = repo.all()
        _skills.value = list
        return list
    }
}
