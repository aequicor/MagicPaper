package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.Id

/**
 * Политика установки навыков (по образцу магазинов скиллов, fail-closed):
 *  - переустановка навыка того же источника обновляет его (тот же id, история сохраняется);
 *  - коллизия имени с навыком другого источника — отказ, никакого тихого перезаписывания.
 */
class SkillInstaller(private val repo: SkillRepository) {

    sealed class Outcome {
        data class Installed(val skill: Skill, val updated: Boolean) : Outcome()
        data class Conflict(val message: String) : Outcome()
    }

    suspend fun installFromCatalog(entry: CatalogEntry): Outcome =
        commit(
            Skill(
                id = Id.new(),
                name = entry.name,
                description = entry.description,
                instructions = entry.instructions,
                tags = entry.tags,
                source = SkillSource.CATALOG,
            )
        )

    suspend fun installDraft(draft: SkillDraft): Outcome =
        commit(
            Skill(
                id = Id.new(),
                name = draft.name,
                description = draft.description,
                instructions = draft.instructions,
                source = SkillSource.SELF_MADE,
            )
        )

    private suspend fun commit(incoming: Skill): Outcome {
        if (incoming.name.isBlank() || incoming.instructions.isBlank()) {
            return Outcome.Conflict("У навыка должны быть имя и инструкция.")
        }
        val existing = repo.all().firstOrNull { it.nameKey == incoming.nameKey }
        if (existing != null && existing.source != incoming.source) {
            return Outcome.Conflict(
                "Имя «${incoming.name}» уже занято навыком из другого источника."
            )
        }
        val now = Id.now()
        val skill = if (existing != null) {
            incoming.copy(id = existing.id, createdAt = existing.createdAt, updatedAt = now)
        } else {
            incoming.copy(createdAt = now, updatedAt = now)
        }
        repo.save(skill)
        return Outcome.Installed(skill, updated = existing != null)
    }
}
