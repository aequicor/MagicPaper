package io.aequicor.magicpaper.domain

/** Read-only installed catalog; writes belong to [SkillCommands]. */
interface SkillRepository {
    suspend fun all(): List<Skill>
}
