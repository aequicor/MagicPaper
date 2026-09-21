package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.Id

/** Builds install values; conflict, identity and source rules belong to the catalog owner. */
class SkillInstaller(private val commands: SkillCommands) {
    suspend fun installFromCatalog(entry: CatalogEntry, expected: SkillInstallBasis): SkillInstallOutcome =
        commands.install(Skill(id = Id.new(), name = entry.name, description = entry.description,
            instructions = entry.instructions, tags = entry.tags.toList(), source = SkillSource.CATALOG), expected)

    suspend fun installDraft(draft: SkillDraft, expected: SkillInstallBasis): SkillInstallOutcome =
        commands.install(Skill(id = Id.new(), name = draft.name, description = draft.description,
            instructions = draft.instructions, source = SkillSource.SELF_MADE), expected)
}
