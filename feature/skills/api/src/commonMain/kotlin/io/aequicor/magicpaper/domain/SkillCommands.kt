package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.StateFlow

data class SkillCatalogRevision(val generation: String, val revision: Long)
data class SkillCatalogItem(val ref: SkillRef, val skill: Skill)
data class SkillCatalogSnapshot(
    val initialized: Boolean = false,
    val revision: SkillCatalogRevision = SkillCatalogRevision("", 0),
    val items: List<SkillCatalogItem> = emptyList(),
    val nameVersions: Map<String, Long> = emptyMap(),
    val unknown: Boolean = false,
    val resetting: Boolean = false,
    val failure: String? = null,
) {
    fun installBasis(name: String): SkillInstallBasis? {
        if (!initialized || unknown || resetting) return null
        val key = name.trim().lowercase()
        return SkillInstallBasis(revision.generation, key,
            items.singleOrNull { it.skill.nameKey == key }?.ref, nameVersions[key])
    }
}

sealed interface SkillInstallOutcome {
    data class Installed(val skill: Skill, val updated: Boolean) : SkillInstallOutcome
    data class Conflict(val message: String) : SkillInstallOutcome
}

/** One semantic writer; no caller can submit facts or replace a persisted owner snapshot. */
interface SkillCommands {
    val catalog: StateFlow<SkillCatalogSnapshot>
    suspend fun start()
    suspend fun reload()
    suspend fun install(skill: Skill, expected: SkillInstallBasis): SkillInstallOutcome
    suspend fun setEnabled(expected: SkillRef, enabled: Boolean)
    suspend fun delete(expected: SkillRef)
    suspend fun importSkills(skills: List<Skill>, expected: SkillCatalogRevision)
    suspend fun clearSkills(expected: SkillCatalogRevision)
    suspend fun prepareForReset()
    suspend fun finishReset()
}
