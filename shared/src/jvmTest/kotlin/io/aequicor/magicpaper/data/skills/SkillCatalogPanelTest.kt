package io.aequicor.magicpaper.data.skills

import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import io.aequicor.magicpaper.plugins.builtin.SkillCatalogPanel
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillCatalogPanelTest {
    @Test fun catalogRendersAtNarrowAndDesktopWidths() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val root = Files.createTempDirectory("catalog-panel-")
        try {
            LocalSkillRepository(root, GithubSkillCatalog.HOST).use { repo ->
                val output = Files.createDirectories(Path.of("build/reports/skills-catalog-ui"))
                for (width in listOf(440, 1000)) {
                    ImageComposeScene(width, 700) { MagicPaperTheme { Surface { SkillCatalogPanel({ repo }) { } } } }.use { scene ->
                        repeat(10) { frame ->
                            scene.render(frame * 16_000_000L).close()
                            withContext(Dispatchers.Default) { delay(30) }; runCurrent()
                        }
                        scene.render(176_000_000L).use { image -> image.encodeToData()!!.use { Files.write(output.resolve("catalog-$width.png"), it.bytes) } }
                    }
                }
            }
        } finally { Dispatchers.resetMain(); root.toFile().deleteRecursively() }
    }
}
