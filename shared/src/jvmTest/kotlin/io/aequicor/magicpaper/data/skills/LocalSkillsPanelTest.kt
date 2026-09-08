package io.aequicor.magicpaper.data.skills

import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.builtin.LocalSkillsPlugin
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSkillsPanelTest {
    @Test fun quarantinedLibraryRendersAtNarrowAndDesktopWidths() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val base = Files.createTempDirectory("skills-panel-")
        try {
            val input = Files.createDirectory(base.resolve("input"))
            Files.writeString(input.resolve("SKILL.md"), "Составь краткое резюме выбранного текста.")
            val root = base.resolve("repo")
            val host = SkillPackageHost("1.0.0", "desktop")
            LocalSkillRepository(root, host).use { repo ->
                SkillPackageImporter(repo, host).directory(input, SkillLocalMetadata("local.summary", "1.0.0", "Резюме", "Для выбранного текста", SkillCompatibility("1.0.0", "2.0.0", setOf("desktop"))))
                val instructions = Files.readAllBytes(input.resolve("SKILL.md"))
                val manifest = SkillPackageManifest(
                    id = "local.summary", version = "2.0.0", name = "Резюме", description = "Для выбранного текста",
                    files = listOf(SkillPackageFile("SKILL.md", SkillPackageValidator.sha256(instructions), instructions.size.toLong())),
                    origin = SkillPackageOrigin(SkillImportKind.LOCAL_DIRECTORY, "https://example.org/skills/summary"),
                    license = "MIT", permissions = setOf(SkillPermission.READ_PROJECT, SkillPermission.NETWORK),
                    compatibility = SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")),
                )
                Files.writeString(input.resolve(SkillPackageFormat.MANIFEST), SkillPackageFormat.json.encodeToString(manifest))
                SkillPackageImporter(repo, host).directory(input)
                val imported = repo.catalog().first()
                assertEquals(manifest, imported.release.pkg.manifest)
                assertEquals(input.toAbsolutePath().normalize().toString(), imported.source.location)
                assertEquals(SkillCandidateStatus.QUARANTINED, imported.release.status)
            }
            val output = Files.createDirectories(Path.of("build/reports/local-skills"))
            for (width in listOf(440, 1000)) {
                LocalSkillsPlugin(root).use { plugin ->
                    ImageComposeScene(width, 860) { MagicPaperTheme { Surface { plugin.Content() } } }.use { scene ->
                        repeat(8) { frame ->
                            scene.render(frame * 16_000_000L).close()
                            withContext(Dispatchers.Default) { delay(25) }
                            runCurrent()
                        }
                        scene.render(160_000_000L).use { image ->
                            image.encodeToData()!!.use { Files.write(output.resolve("library-$width.png"), it.bytes) }
                        }
                    }
                }
            }
        } finally {
            Dispatchers.resetMain()
            Files.walk(base).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
