package io.aequicor.magicpaper.data.skills

import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.builtin.LocalExperiencePlugin
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalExperiencePanelTest {
    @Test fun journalPanelRendersAtNarrowAndDesktopWidthsWithoutCallingLlm() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val base = Files.createTempDirectory("experience-panel-")
        val profiles = object : LlmProfileRepository {
            override suspend fun load() = emptyList<LlmProfile>()
            override suspend fun save(profile: LlmProfile) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun replaceAll(profiles: List<LlmProfile>) = Unit
        }
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("UI must not send requests")
        }
        try {
            LocalSkillRepository(base.resolve("repo"), SkillPackageHost("1.0.0", "desktop")).use { repo ->
                LocalSkillExperience(base.resolve("experience"), repo, gateway, { emptyList() }).use { j ->
                    j.record(ExperienceScenario.SUMMARY, true, setOf(ExperienceFeature.TOO_LONG))
                    val plugin = LocalExperiencePlugin(j, profiles)
                    val output = Files.createDirectories(Path.of("build/reports/local-experience"))
                    for (width in listOf(440, 1000)) {
                        ImageComposeScene(width, 1000) { MagicPaperTheme { Surface { plugin.Content() } } }.use { scene ->
                            repeat(8) { frame ->
                                scene.render(frame * 16_000_000L).close()
                                withContext(Dispatchers.Default) { delay(25) }
                                runCurrent()
                            }
                            scene.render(160_000_000L).use { image -> image.encodeToData()!!.use {
                                Files.write(output.resolve("journal-$width.png"), it.bytes)
                            } }
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
