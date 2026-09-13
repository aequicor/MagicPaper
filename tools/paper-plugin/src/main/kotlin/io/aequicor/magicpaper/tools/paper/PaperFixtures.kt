package io.aequicor.magicpaper.tools.paper

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.ui.components.*
import io.aequicor.visualization.editor.plugins.*
import io.aequicor.visualization.engine.ir.model.PropValue
import io.aequicor.visualization.engine.ir.resolve.ExternalComponent

internal data class PaperFixture(
    val definition: PluginComponent,
    val content: @Composable (PaperFixtureState, Modifier) -> Unit,
)
internal class PaperFixtureState(value: ExternalComponent, val interactive: Boolean) {
    val control = value.variant["state"]?.let { PaperControlState.valueOf(it) } ?: PaperControlState.NORMAL
    val enabled = control != PaperControlState.DISABLED
    val buttonKind = value.variant["kind"]?.let { PaperButtonKind.valueOf(it) } ?: PaperButtonKind.PRIMARY
    val initialText = (value.props["text"] as? PropValue.Text)?.value ?: "Пример Paper"
    var text by mutableStateOf(initialText)
    var checked by mutableStateOf((value.props["checked"] as? PropValue.Bool)?.value ?: false)
    var opened by mutableStateOf(false)
    var count by mutableStateOf(0)
    val progress = ((value.props["progress"] as? PropValue.Number)?.value ?: 0.62).toFloat().coerceIn(0f, 1f)
    fun activate() { if (interactive) { count++; checked = !checked } }
    fun updateText(next: String) { if (interactive) text = next }
    fun toggle() { if (interactive) checked = !checked }
}
private val text = PluginProperty("text", "Текст", PluginPropertyType.TEXT, PropValue.Text("Пример Paper"))
private val checked = PluginProperty("checked", "Выбрано", PluginPropertyType.BOOLEAN, PropValue.Bool(false))
private val progress = PluginProperty("progress", "Прогресс", PluginPropertyType.NUMBER, PropValue.Number(0.62))
private val desktopVariants = mapOf("platform" to listOf("macOS", "Windows"), "textScale" to listOf("1", "1.5", "2"))
private fun fixture(id: String, group: String, width: Int = 280, height: Int = 72,
    properties: List<PluginProperty> = listOf(text), states: List<String> = emptyList(),
    variants: Map<String, List<String>> = emptyMap(),
    content: @Composable (PaperFixtureState, Modifier) -> Unit,
) = PaperFixture(PluginComponent(id, id, group, width, height, properties,
    desktopVariants + variants + (if (states.isEmpty()) emptyMap() else mapOf("state" to states)),
    variantLabels = mapOf("platform" to "Платформа", "textScale" to "Масштаб текста", "kind" to "Вид", "state" to "Состояние"),
    variantValueLabels = mapOf(
        "textScale" to mapOf("1" to "100%", "1.5" to "150%", "2" to "200%"),
        "kind" to mapOf("PRIMARY" to "Основная", "SECONDARY" to "Вторичная", "DESTRUCTIVE" to "Удаление", "QUIET" to "Спокойная"),
        "state" to mapOf("NORMAL" to "Обычное", "HOVER" to "Наведение", "PRESSED" to "Нажатие", "FOCUSED" to "Фокус", "DISABLED" to "Недоступно", "SELECTED" to "Выбрано", "ERROR" to "Ошибка", "BUSY" to "Загрузка"),
    )), content)
private val controlStates = listOf("NORMAL", "DISABLED")

internal val paperFixtures: List<PaperFixture> = listOf(
    fixture("PaperText", "Основы") { s, m -> PaperText(s.text, m) },
    fixture("PaperButton", "Контролы", states = PaperControlState.entries.map { it.name }, variants = mapOf("kind" to PaperButtonKind.entries.map { it.name })) { s, m ->
        PaperButton(if (s.count == 0) s.text else "Нажато: ${s.count}", s::activate, m, kind = s.buttonKind, state = s.control)
    },
    fixture("PaperIconButton", "Контролы", 72, 72, listOf(text, checked), controlStates) { s, m ->
        PaperIconButton(s.text, s::activate, m, enabled = s.enabled, selected = s.checked) { PaperText("+") }
    },
    fixture("PaperField", "Контролы", height = 116, states = listOf("NORMAL", "DISABLED", "ERROR")) { s, m ->
        PaperField(s.text, s::updateText, "Название", m, enabled = s.enabled, errorMessage = if (s.control == PaperControlState.ERROR) "Проверьте значение" else null)
    },
    fixture("PaperSwitch", "Контролы", properties = listOf(text, checked), states = controlStates) { s, m ->
        PaperSwitch(s.checked, { s.toggle() }, s.text, m, s.enabled)
    },
    fixture("PaperCheck", "Контролы", 72, 72, listOf(checked), controlStates) { s, m -> PaperCheck(s.checked, { s.toggle() }, m, s.enabled) },
    fixture("PaperChoice", "Контролы", properties = listOf(text, checked), states = controlStates) { s, m -> PaperChoice(s.checked, s::toggle, s.text, m, s.enabled) },
    fixture("PaperProgress", "Статусы", properties = listOf(text, progress)) { s, m -> PaperProgress(m, s.progress, label = s.text) },
    fixture("PaperIndeterminateProgress", "Статусы") { s, m -> PaperIndeterminateProgress(m, s.text) },
    fixture("PaperStatus", "Статусы", states = listOf("NORMAL", "ERROR")) { s, m -> PaperStatus(s.text, m, s.control == PaperControlState.ERROR) },
    fixture("PaperActivityIndicator", "Статусы") { s, m -> PaperActivityIndicator(PaperActivityTone.WORKING, s.text, m, running = true) },
    fixture("PaperActivityIndicatorButton", "Статусы", properties = listOf(text, checked), states = controlStates) { s, m ->
        PaperActivityIndicatorButton(PaperActivityTone.READY, s.text, s::toggle, m, selected = s.checked, enabled = s.enabled)
    },
    fixture("PaperContextIndicator", "Статусы", properties = listOf(text, progress)) { s, m -> PaperContextIndicator(s.progress, s.text, s::activate, m) },
    fixture("PaperListRow", "Списки", properties = listOf(text, checked), states = controlStates) { s, m -> PaperListRow(s.text, m, s.checked, s.enabled, s::toggle, "Дополнительная подпись") },
    fixture("PaperTreeRow", "Списки", properties = listOf(text, checked), states = controlStates) { s, m -> PaperTreeRow(s.text, m, s.checked, s.checked, s.enabled, s::toggle) },
    fixture("PaperTreeGroupHeader", "Списки", properties = listOf(text, checked)) { s, m -> PaperTreeGroupHeader(s.text, s.checked, s::toggle, m, active = s.checked, childCount = 3) },
    fixture("PaperTab", "Навигация", properties = listOf(text, checked)) { s, m -> PaperTab(s.text, s.checked, s::toggle, m) },
    fixture("PaperLink", "Навигация") { s, m -> PaperLink(if (s.count == 0) s.text else "Открыто", s::activate, m) },
    fixture("PaperToolbarButton", "Навигация", 72, 72) { s, m -> Box(m) { PaperToolbarButton(PaperToolbarIcon.Settings, s.text, LocalPaperPlatformPolicy.current.density.controlHeight, onClick = s::activate) } },
    fixture("PaperPanel", "Контейнеры", height = 140) { s, m -> PaperPanel(m) { PaperText(s.text) } },
    fixture("PaperSurface", "Контейнеры", height = 140) { s, m -> PaperSurface(m) { PaperText(s.text) } },
    fixture("PaperList", "Контейнеры", height = 220) { s, m -> PaperList(m) { Column { repeat(3) { PaperListRow("${s.text} ${it + 1}", onClick = s::activate) } } } },
    fixture("PaperScrollArea", "Контейнеры", height = 200) { s, m -> PaperScrollArea(m) { Column { repeat(16) { PaperText("${s.text} ${it + 1}") } } } },
    fixture("PaperMarkdown", "Текст", height = 220, properties = listOf(text.copy(default = PropValue.Text("# Заголовок\n\nТекст **Paper** и список:\n- Первый пункт\n- Второй пункт")))) { s, m -> PaperMarkdown(s.text, m) },
    fixture("PaperCodeBlock", "Текст", height = 120) { s, m -> PaperCodeBlock(m) { PaperText(s.text, role = PaperTextRole.CODE) } },
    fixture("PaperReader", "Текст", height = 220) { s, m -> PaperReader(m) { PaperMarkdown(s.text) } },
    fixture("PaperFadingText", "Текст") { s, m -> PaperFadingText(s.text, m) },
    fixture("PaperChatTranscript", "Сообщения", height = 220) { s, m -> PaperChatTranscript(m) { Column { PaperText(s.text); PaperSystemMessage { PaperText("Сообщение системы") } } } },
    fixture("PaperSystemMessage", "Сообщения", height = 100) { s, m -> PaperSystemMessage(m) { PaperText(s.text) } },
    fixture("PaperPinnedMessage", "Сообщения") { s, m -> PaperPinnedMessage(1, s::activate, m) { PaperText(s.text) } },
    fixture("PaperAttachmentChip", "Сообщения") { s, m -> PaperAttachmentChip(s.text, if (s.interactive) ({ s.text = "Удалено" }) else null, m) },
    fixture("PaperComposer", "Рабочая область", height = 110) { s, m -> PaperComposer(m) { PaperPromptField(s.text, s::updateText, "Сообщение", Modifier.weight(1f)); PaperButton("Отправить", s::activate) } },
    fixture("PaperWorkspaceComposer", "Рабочая область", height = 150) { s, m -> PaperWorkspaceComposer(m) { PaperPromptField(s.text, s::updateText, "Сообщение"); PaperButton("Готово", s::activate) } },
    fixture("PaperPromptField", "Рабочая область", height = 100, states = controlStates) { s, m -> PaperPromptField(s.text, s::updateText, "Сообщение", m, enabled = s.enabled) },
    fixture("PaperWorkSurface", "Рабочая область", height = 180) { s, m -> PaperWorkSurface(m) { PaperWorkspaceHeading(s.text, "Подзаголовок"); PaperText("Содержимое") } },
    fixture("PaperWorkspaceHeading", "Рабочая область", height = 100) { s, m -> PaperWorkspaceHeading(s.text, "Подзаголовок", m) },
    fixture("PaperResizablePanels", "Рабочая область", width = 720, height = 240) { s, m -> PaperResizablePanels(m, sidebar = { PaperPanel(it) { PaperListRow(s.text) } }) { PaperText("Рабочая область") } },
    fixture("PaperStatusPanel", "Контейнеры", height = 140) { s, m -> PaperStatusPanel(m) { PaperStatus(s.text) } },
    fixture("PaperApprovalDock", "Контейнеры", height = 140) { s, m -> PaperApprovalDock(m) { Column { PaperText(s.text); PaperButton("Подтвердить", s::activate) } } },
    fixture("PaperQuestionnaire", "Контейнеры", height = 160) { s, m -> PaperQuestionnaire(m) { PaperField(s.text, s::updateText, "Ответ") } },
    fixture("PaperWizard", "Контейнеры", height = 140) { s, m -> PaperWizard(m) { Column { PaperText(s.text); PaperButton("Далее", s::activate) } } },
    fixture("PaperScheduleEditor", "Контейнеры", height = 140) { s, m -> PaperScheduleEditor(m) { PaperField(s.text, s::updateText, "Расписание") } },
    fixture("PaperGraph", "Граф", height = 180) { s, m -> PaperGraph(m) { PaperGraphNode(s.checked, false, false, s::toggle) { PaperText(s.text) } } },
    fixture("PaperGraphNode", "Граф", height = 100, properties = listOf(text, checked)) { s, m -> PaperGraphNode(s.checked, false, false, s::toggle, m) { PaperText(s.text) } },
    fixture("PaperGraphTerminal", "Граф", 110, 110) { s, m -> PaperGraphTerminal(s.text, true, m) },
    fixture("PaperPluginRow", "Списки", height = 120, properties = listOf(text, checked)) { s, _ -> PaperPluginRow(s.text, "Описание", "◇", s.checked, s::toggle) },
    fixture("PaperMenu", "Наложения", height = 72) { s, m -> Box(m) {
        PaperButton(s.text, { if (s.interactive) s.opened = true })
        PaperMenu(s.opened, { s.opened = false }, listOf(PaperMenuItem("Первый пункт", onClick = s::activate), PaperMenuItem("Второй пункт", onClick = s::activate)))
    } },
    fixture("PaperDialog", "Наложения", height = 72) { s, m ->
        PaperButton(s.text, { if (s.interactive) s.opened = true }, m)
        if (s.opened) PaperDialog(s.text, { s.opened = false }, confirmLabel = "Готово", onConfirm = { s.opened = false }) { PaperField(s.text, s::updateText, "Название") }
    },
    fixture("PaperTooltip", "Наложения", height = 72) { s, m -> PaperTooltip(s.text, m) { PaperButton("Навести указатель", s::activate) } },
    fixture("PaperCoinIcon", "Основы", 40, 40, properties = emptyList()) { _, m -> PaperCoinIcon(m) },
    fixture("PaperDivider", "Основы", height = 24, properties = emptyList()) { _, m -> Box(m) { PaperDivider() } },
    fixture("PaperChatPlainText", "Сообщения", height = 180) { s, m -> PaperChatPlainText(s.text, m) },
    fixture("PaperMessagePreview", "Сообщения", height = 220, properties = listOf(text, checked)) { s, m ->
        PaperMessagePreview(s.text, s.checked, m, preview = { PaperText(s.text) }, reader = { PaperText(s.text, it) })
    },
    fixture("PaperSessionContextMessage", "Сообщения", height = 220) { s, m -> Box(m) { PaperSessionContextMessage("fixture", s.text) } },

)
