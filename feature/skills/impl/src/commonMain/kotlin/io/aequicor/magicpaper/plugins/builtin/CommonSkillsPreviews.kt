package io.aequicor.magicpaper.plugins.builtin

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.MagicPlugin
import kotlinx.coroutines.flow.MutableStateFlow

/** Isolated visual fixtures; real plugin composables and the public command boundary. */
internal enum class CommonSkillsPreviewState { DEFAULT, EMPTY, LOADING, ERROR }

internal fun commonSkillsPreviewPlugin(education: Boolean, state: CommonSkillsPreviewState): MagicPlugin {
    val values = if (state in setOf(CommonSkillsPreviewState.EMPTY, CommonSkillsPreviewState.LOADING)) emptyList() else listOf(
        Skill("summary", "Резюме текста", "Для краткого пересказа выбранного текста", "Сохрани выводы и существенные детали."),
        Skill("own", "Проверка последовательности действий", "Когда нужно объяснить задачу по шагам", "Проверь порядок шагов.", source = SkillSource.SELF_MADE, enabled = false),
    )
    val ready = state != CommonSkillsPreviewState.LOADING
    val snapshot = SkillCatalogSnapshot(initialized = ready, revision = SkillCatalogRevision("preview", 1),
        items = values.map { SkillCatalogItem(SkillRef(it.id, "preview", 1), it) },
        nameVersions = values.associate { it.nameKey to 1L }, unknown = state == CommonSkillsPreviewState.ERROR,
        failure = if (state == CommonSkillsPreviewState.ERROR) "Не удалось сохранить или загрузить навыки. Повторите действие." else null)
    val commands = object : SkillCommands {
        override val catalog = MutableStateFlow(snapshot)
        override suspend fun start() = Unit
        override suspend fun reload() { catalog.value = snapshot.copy(unknown = false, failure = null) }
        override suspend fun install(skill: Skill, expected: SkillInstallBasis): SkillInstallOutcome = error("Static preview")
        override suspend fun setEnabled(expected: SkillRef, enabled: Boolean): Unit = error("Static preview")
        override suspend fun delete(expected: SkillRef): Unit = error("Static preview")
        override suspend fun importSkills(skills: List<Skill>, expected: SkillCatalogRevision): Unit = error("Static preview")
        override suspend fun clearSkills(expected: SkillCatalogRevision): Unit = error("Static preview")
        override suspend fun prepareForReset(): Unit = error("Static preview")
        override suspend fun finishReset(): Unit = error("Static preview")
    }
    if (!education) return SkillsRepositoryPlugin(object : SkillCatalog {
        val entry = CatalogEntry("summary", "Резюме текста", "Для краткого пересказа выбранного текста", "Сохрани выводы.")
        override suspend fun entries() = listOf(entry)
        override suspend fun search(query: String, limit: Int) = entries().filter { it.name.contains(query, true) }.take(limit)
    }, SkillInstaller(commands), commands)
    return SelfEducationPlugin(SkillEducator(object : LlmGateway {
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Preview must never call a provider")
    }), SkillInstaller(commands), commands, object : ChatRepository {
        override suspend fun sessions() = emptyList<ChatSession>()
        override suspend fun session(id: String): ChatSession? = null
    }, object : SettingsRepository { override suspend fun load() = AppSettings() },
        object : LlmProfileRepository { override suspend fun load() = emptyList<LlmProfile>() })
}

@Composable
internal fun CommonSkillsPreview(education: Boolean, state: CommonSkillsPreviewState) {
    val plugin = remember(education, state) { commonSkillsPreviewPlugin(education, state) }
    PaperTheme { PaperSurface(Modifier.fillMaxSize().padding(LocalPaperSpacing.current.md)) { plugin.Content() } }
}

@Preview(name = "Catalog default", group = "Common skills", widthDp = 640, heightDp = 700)
@Composable internal fun SkillsCatalogDefaultPreview() = CommonSkillsPreview(false, CommonSkillsPreviewState.DEFAULT)
@Preview(name = "Catalog empty", group = "Common skills", widthDp = 440, heightDp = 700)
@Composable internal fun SkillsCatalogEmptyPreview() = CommonSkillsPreview(false, CommonSkillsPreviewState.EMPTY)
@Preview(name = "Catalog loading", group = "Common skills", widthDp = 440, heightDp = 700)
@Composable internal fun SkillsCatalogLoadingPreview() = CommonSkillsPreview(false, CommonSkillsPreviewState.LOADING)
@Preview(name = "Catalog error narrow", group = "Common skills", widthDp = 360, heightDp = 900)
@Preview(name = "Catalog error large text", group = "Common skills", widthDp = 480, heightDp = 1100, fontScale = 1.6f)
@Composable internal fun SkillsCatalogErrorPreview() = CommonSkillsPreview(false, CommonSkillsPreviewState.ERROR)
@Preview(name = "Education default", group = "Common skills", widthDp = 640, heightDp = 700)
@Composable internal fun SkillsEducationDefaultPreview() = CommonSkillsPreview(true, CommonSkillsPreviewState.DEFAULT)
@Preview(name = "Education empty", group = "Common skills", widthDp = 440, heightDp = 700)
@Composable internal fun SkillsEducationEmptyPreview() = CommonSkillsPreview(true, CommonSkillsPreviewState.EMPTY)
@Preview(name = "Education loading", group = "Common skills", widthDp = 440, heightDp = 700)
@Composable internal fun SkillsEducationLoadingPreview() = CommonSkillsPreview(true, CommonSkillsPreviewState.LOADING)
@Preview(name = "Education error narrow", group = "Common skills", widthDp = 360, heightDp = 900)
@Preview(name = "Education error large text", group = "Common skills", widthDp = 480, heightDp = 1100, fontScale = 1.6f)
@Composable internal fun SkillsEducationErrorPreview() = CommonSkillsPreview(true, CommonSkillsPreviewState.ERROR)
