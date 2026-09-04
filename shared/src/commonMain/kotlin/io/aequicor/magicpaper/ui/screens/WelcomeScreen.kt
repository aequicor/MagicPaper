package io.aequicor.magicpaper.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.domain.ProviderCatalog
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.window.LocalWindowTitleBarInsets
import io.aequicor.magicpaper.util.Id

/**
 * Ознакомительный тур при первом запуске: профиль модели → поиск → плагины.
 * Завершается сохранением черновика настроек и флагом onboardingDone.
 */
@Composable
fun WelcomeScreen(
    vm: MagicPaperViewModel,
    settings: AppSettings,
    plugins: List<MagicPlugin>,
    states: Map<String, PluginState>,
) {
    var page by remember { mutableIntStateOf(0) }
    var draft by remember(settings) { mutableStateOf(settings) }
    // Черновик профиля подключения: шаг 1 собирает его, завершение тура сохраняет.
    var profileDraft by remember {
        mutableStateOf(LlmProfile(id = Id.new(), name = "Мой источник", createdAt = Id.now()))
    }
    val lastPage = 3

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Верхняя строка: точки-прогресс и «пропустить».
        // На macOS — отступ слева под нативный «светофор».
        val layoutDirection = LocalLayoutDirection.current
        val trafficLights = LocalWindowTitleBarInsets.current.calculateLeftPadding(layoutDirection)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = trafficLights),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageDots(page = page, pageCount = lastPage + 1, modifier = Modifier.weight(1f))
            if (page == 0) {
                TextButton(onClick = { vm.finishOnboarding(draft, profileDraft) }) { Text("Пропустить") }
            }
        }
        Spacer(Modifier.height(16.dp))

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(220)) { w -> dir * w } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(220)) { w -> -dir * w } + fadeOut(tween(120)))
            },
            label = "welcome-page",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { p ->
            Box(
                modifier = Modifier.fillMaxSize().widthIn(max = 560.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (p) {
                        0 -> WelcomeIntro()
                        1 -> WelcomeModel(profileDraft) { profileDraft = it }
                        2 -> WelcomeSearch(draft) { draft = it }
                        else -> WelcomePlugins(plugins, states) { id, on -> vm.togglePlugin(id, on) }
                    }
                }
            }
        }

        // Нижняя навигация тура.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (page > 0) {
                TextButton(
                    onClick = { page-- },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("← Назад") }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            if (page < lastPage) {
                Button(onClick = { page++ }) { Text("Далее →") }
            } else {
                Button(onClick = { vm.finishOnboarding(draft, profileDraft) }) { Text("Начать работу") }
            }
        }
    }
}

@Composable
private fun PageDots(page: Int, pageCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { i ->
            Box(
                modifier = Modifier
                    .size(if (i == page) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == page) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeIntro() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✦", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Добро пожаловать в MagicPaper", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Это «магическая бумага»: задайте вопрос — агент поднимет ваши документы, " +
                "поиск в сети и ответит прямо в свитке.\n\n" +
                "За три шага настроим источник магии (модель), поисковый движок и плагины. " +
                "Всё можно поменять позже в настройках.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WelcomeModel(draft: LlmProfile, onDraft: (LlmProfile) -> Unit) {
    val spec = ProviderCatalog.all.firstOrNull { it.displayName == draft.name }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Шаг 1 — источник магии", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Выберите провайдера: локальный сервер (Ollama, LM Studio) или облачный API. " +
                "Позже можно подключить сколько угодно источников и переключать их в чате.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        ProviderCatalog.all.forEach { candidate ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = {
                        onDraft(
                            draft.copy(
                                provider = candidate.type,
                                name = candidate.displayName,
                                baseUrl = candidate.defaultBaseUrl.ifBlank { draft.baseUrl },
                                modelId = candidate.models.firstOrNull()?.id.orEmpty().ifBlank { draft.modelId },
                            ),
                        )
                    })
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (candidate.displayName == draft.name) "◉" else "○",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (candidate.displayName == draft.name) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(10.dp))
                Text(candidate.displayName, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(10.dp))
        Field("Base URL", draft.baseUrl) { onDraft(draft.copy(baseUrl = it)) }
        Field("API-ключ (${spec?.keyHint ?: "пусто для локальных серверов"})", draft.apiKey) {
            onDraft(draft.copy(apiKey = it))
        }
        Field("Имя модели", draft.modelId) { onDraft(draft.copy(modelId = it)) }
        Spacer(Modifier.height(8.dp))
        if (draft.configured) {
            Text("✓ Источник готов", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun WelcomeSearch(draft: AppSettings, onDraft: (AppSettings) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Шаг 2 — поиск", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "«Авто» пробует Wikipedia, Querit и Google по очереди; ключи можно добавить позже.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        SearchProviderPicker(draft.searchProvider) { onDraft(draft.copy(searchProvider = it)) }
        Spacer(Modifier.height(8.dp))
        Field("Querit API-ключ (необязательно)", draft.queritApiKey) { onDraft(draft.copy(queritApiKey = it)) }
        Field("Google API-ключ (необязательно)", draft.googleApiKey) { onDraft(draft.copy(googleApiKey = it)) }
        Field("Google Search Engine ID", draft.googleSearchEngineId) {
            onDraft(draft.copy(googleSearchEngineId = it))
        }
    }
}

@Composable
private fun WelcomePlugins(
    plugins: List<MagicPlugin>,
    states: Map<String, PluginState>,
    onToggle: (String, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Шаг 3 — плагины", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Включите нужные панели — их можно переключать в любой момент в разделе «Плагины».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        plugins.forEach { plugin ->
            val enabled = states[plugin.id]?.enabled ?: true
            PluginRow(plugin, enabled) { onToggle(plugin.id, !enabled) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
