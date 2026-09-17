package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.ChatState

@Preview(name = "Source tabs", group = "Research sources", widthDp = 340, heightDp = 500)
@Preview(name = "Source tabs narrow", group = "Research sources", widthDp = 240, heightDp = 580)
@Preview(name = "Source tabs large text", group = "Research sources", widthDp = 400, heightDp = 850, fontScale = 2f)
@Composable
internal fun ResearchSourcesPanePreview(question: Boolean = false, saving: Boolean = false) = PaperTheme {
    val resources = remember { listOf(
        ResearchResource("kotlin", "Kotlin для AI-приложений", "https://kotlinlang.org/docs/ai-overview.html"),
        ResearchResource("spring", "Spring AI: начало работы", "https://spring.io/projects/spring-ai"),
        ResearchResource("android", "LLM Inference для Android", "https://developers.google.com/edge"),
        ResearchResource("tracy", "Tracy: наблюдаемость AI", "https://blog.jetbrains.com/kotlin/tracy"),
        ResearchResource("kotlinllm", "KotlinLLM: открытый код", "https://blog.jetbrains.com/kotlin/kotlinllm"),
        ResearchResource("unavailable", "Практика интеграции", "https://example.org/integration"),
    ) }
    var current by remember { mutableStateOf(ChatSession("sources-preview", "Источники", 1, 1,
        resources = resources, disabledResourceKeys = setOf(resources.last().key))) }
    var target by remember { mutableStateOf(if (question) ResearchResourceScope.QUESTION else ResearchResourceScope.SHARED) }
    PaperSurface(Modifier.fillMaxSize(), kind = PaperSurfaceKind.CANVAS) {
        PaperResearchPane(Modifier.fillMaxSize().padding(8.dp)) {
            ResearchSourcesPane(
                ChatState(current = current, sessions = listOf(current), sourceBrowserSupported = true,
                    sourceReadProblems = mapOf(current.id to mapOf(resources.last().key to "Ошибка HTTP 403"))),
                saving = saving, target = target, onScopeChange = { target = it },
                onAddWebsite = { _, _ -> Result.success(Unit) }, onSearchResources = { Result.success(emptyList()) },
                onAddSearchResult = { _, _ -> Result.success(Unit) }, onPickFiles = {},
                onRemoveResource = { id, _ -> current = current.copy(resources = current.resources.filterNot { it.id == id }) },
                onResourceEnabled = { key, enabled -> current = current.copy(disabledResourceKeys =
                    if (enabled) current.disabledResourceKeys - key else current.disabledResourceKeys + key) },
                onShareResource = {}, onCollapse = {}, loadSourceIcon = { null },
                onResourcesEnabled = { keys, enabled -> current = current.copy(disabledResourceKeys =
                    if (enabled) current.disabledResourceKeys - keys else current.disabledResourceKeys + keys) },
                onReadSourceInBrowser = {})
        }
    }
}

@Preview(name = "Empty question sources", group = "Research sources", widthDp = 340, heightDp = 280)
@Composable
internal fun ResearchQuestionSourcesEmptyPreview() = ResearchSourcesPanePreview(question = true)

@Preview(name = "Saving source selection", group = "Research sources", widthDp = 340, heightDp = 500)
@Composable
internal fun ResearchSourcesSavingPreview() = ResearchSourcesPanePreview(saving = true)
