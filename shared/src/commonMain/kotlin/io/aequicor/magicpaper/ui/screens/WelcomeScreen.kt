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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.domain.ProviderCatalog
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.UiState
import io.aequicor.magicpaper.ui.window.LocalWindowTitleBarInsets
import io.aequicor.magicpaper.ui.window.WindowTitleBarArea
import io.aequicor.magicpaper.util.Id

/**
 * Ознакомительный тур при первом запуске: профиль модели → поиск → плагины.
 * Завершается сохранением черновика настроек и флагом onboardingDone.
 */
@Composable
fun WelcomeScreen(
    vm: MagicPaperViewModel,
    state: UiState,
) {
    val settings = state.settings
    val plugins = state.plugins
    val states = state.pluginStates
    var page by remember { mutableIntStateOf(0) }
    var draft by remember(settings) { mutableStateOf(settings) }
    // Черновик профиля подключения: шаг 1 собирает его, завершение тура сохраняет.
    var profileDraft by remember {
        mutableStateOf(LlmProfile(id = Id.new(), name = "Мой источник", createdAt = Id.now()))
    }
    val lastPage = 3

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Верхняя строка: точки-прогресс и «пропустить». На desktop это также
        // нативная область тайтлбара; кнопки ОС резервируют место по краям.
        val layoutDirection = LocalLayoutDirection.current
        val titleBarInsets = LocalWindowTitleBarInsets.current
        WindowTitleBarArea(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    start = 24.dp + titleBarInsets.calculateLeftPadding(layoutDirection),
                    end = 24.dp + titleBarInsets.calculateRightPadding(layoutDirection),
                    top = 16.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageDots(page = page, pageCount = lastPage + 1, modifier = Modifier.weight(1f))
                if (page == 0) {
                    TextButton(onClick = { vm.finishOnboarding(draft, profileDraft) }) { Text("Пропустить") }
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
                            1 -> WelcomeModel(vm, state, profileDraft) { profileDraft = it }
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
private fun WelcomeModel(vm: MagicPaperViewModel, state: UiState, draft: LlmProfile, onDraft: (LlmProfile) -> Unit) {
    val spec = ProviderCatalog.all.firstOrNull { it.displayName == draft.name }
    val subscription = draft.provider == ProviderType.OPENAI_SUBSCRIPTION
    val uriHandler = LocalUriHandler.current
    androidx.compose.runtime.LaunchedEffect(state.openAiSubscription.login?.url) {
        state.openAiSubscription.login?.url?.let(uriHandler::openUri)
    }
    androidx.compose.runtime.LaunchedEffect(subscription) {
        if (subscription && state.openAiSubscription.account == null && state.openAiSubscription.error == null) {
            vm.refreshOpenAiSubscription()
        }
    }
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
            val enabled = !candidate.desktopOnly || state.openAiSubscription.available
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(enabled = enabled, onClick = {
                        onDraft(
                            draft.copy(
                                provider = candidate.type,
                                name = candidate.displayName,
                                baseUrl = if (candidate.usesSubscription) "" else candidate.defaultBaseUrl.ifBlank { draft.baseUrl },
                                apiKey = if (candidate.usesSubscription) "" else draft.apiKey,
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
                Text(
                    candidate.displayName + if (!enabled) " · только desktop" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (subscription) {
            val auth = state.openAiSubscription
            Text(
                if (auth.account?.signedIn == true) "✓ Вход выполнен: ${auth.account.email.orEmpty()}"
                else "API-ключ не нужен — войдите с аккаунтом ChatGPT.",
                color = if (auth.account?.signedIn == true) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            auth.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (auth.signingIn) {
                    TextButton(onClick = { auth.login?.url?.let(uriHandler::openUri) }) { Text("Открыть вход") }
                    TextButton(onClick = vm::cancelOpenAiSubscriptionLogin) { Text("Отмена") }
                } else if (auth.account?.signedIn == true) {
                    TextButton(onClick = { vm.refreshOpenAiSubscription(true) }) { Text("Обновить аккаунт") }
                } else {
                    TextButton(onClick = vm::startOpenAiSubscriptionLogin) { Text("Войти через ChatGPT") }
                }
            }
        } else {
            Field("Base URL", draft.baseUrl) { onDraft(draft.copy(baseUrl = it)) }
            Field("API-ключ (${spec?.keyHint ?: "пусто для локальных серверов"})", draft.apiKey) {
                onDraft(draft.copy(apiKey = it))
            }
        }
        Field("Имя модели", draft.modelId) { onDraft(draft.copy(modelId = it)) }
        Spacer(Modifier.height(8.dp))
        if (draft.configured && (!subscription || state.openAiSubscription.account?.signedIn == true)) {
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
            "«Авто» пробует настроенные Google и Querit, затем Wikipedia; ключи можно добавить позже.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        SearchProviderPicker(draft.searchProvider) { onDraft(draft.copy(searchProvider = it)) }
        Spacer(Modifier.height(8.dp))
        SearchApiSettings(draft, onDraft)
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
