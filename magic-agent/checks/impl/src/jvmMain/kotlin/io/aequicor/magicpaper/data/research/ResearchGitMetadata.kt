package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.domain.checks.CheckGitMetadataQuery
import java.nio.file.Path
import java.nio.file.Paths

/** Values decoded from exact, completed metadata checks. No process or permission authority lives here. */
internal data class ResearchGitMetadata(val project: Path, val tracked: Set<Path>, val directories: List<Path>) {
    companion object {
        fun decode(project: Path, outputs: Map<CheckGitMetadataQuery, ByteArray>): ResearchGitMetadata {
            require(outputs.keys == CheckGitMetadataQuery.entries.toSet())
            fun text(query: CheckGitMetadataQuery): String {
                val bytes = outputs.getValue(query)
                require(bytes.size <= 16_000_000) { "Слишком большой список Git" }
                return bytes.decodeToString(throwOnInvalidSequence = true)
            }
            val tracked = text(CheckGitMetadataQuery.TRACKED_FILES).split('\u0000').filter(String::isNotBlank).map { name ->
                require(!Paths.get(name).isAbsolute)
                project.resolve(name).normalize().also { require(it != project && it.startsWith(project)) }
            }.toSet()
            val directories = listOf(CheckGitMetadataQuery.GIT_DIRECTORY, CheckGitMetadataQuery.COMMON_DIRECTORY).map { query ->
                val value = text(query).trim()
                require(value.isNotBlank() && '\u0000' !in value)
                val path = Paths.get(value)
                (if (path.isAbsolute) path else project.resolve(path)).toRealPath()
            }
            return ResearchGitMetadata(project, tracked, directories)
        }
    }
}
