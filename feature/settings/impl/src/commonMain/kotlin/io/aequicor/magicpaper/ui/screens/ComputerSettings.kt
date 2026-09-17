package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.*

@Composable
internal fun ComputerSettings(vm: DefaultSettingsComponent, state: SettingsState) {
    val coding by vm.coding.state.collectAsState()
    val permissions by vm.computerPermissions.state.collectAsState()
    LaunchedEffect(state.settings.computerAccess, state.settings.applicationAccess) {
        vm.computerPermissions.refresh(state.settings.computerAccess, state.settings.applicationAccess)
    }
    ComputerSettingsContent(state.settings, coding.coding.computerSupported, coding.coding.applicationSupported,
        state.settingsSaving, permissions, vm::closeComputerSettings, vm::saveComputerAccess,
        { vm.computerPermissions.refresh(state.settings.computerAccess, state.settings.applicationAccess) },
        vm.computerPermissions::open, vm.computerPermissions::reveal)
}

@Composable
internal fun ComputerSettingsContent(
    settings: AppSettings, computerSupported: Boolean, applicationSupported: Boolean,
    saving: Boolean, permissions: ComputerPermissionUi, onBack: () -> Unit, onChange: (AppSettings) -> Unit,
    onRefresh: () -> Unit, onOpen: (ComputerPermission) -> Unit, onReveal: (PermissionTarget) -> Unit,
) {
    PaperScrollColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PaperButton("‹ Настройки", onBack, kind = PaperButtonKind.QUIET)
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PaperText("Управление компьютером", role = PaperTextRole.HEADLINE)
            AutomationSettings(settings, computerSupported, applicationSupported, saving, onChange)
            ComputerPermissionOnboarding(permissions, onRefresh, onOpen, onReveal)
        }
    }
}

@Composable
internal fun ComputerPermissionOnboarding(
    state: ComputerPermissionUi, onRefresh: () -> Unit,
    onOpen: (ComputerPermission) -> Unit, onReveal: (PermissionTarget) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PaperText("Разрешения системы", role = PaperTextRole.TITLE)
        if (state.busy) PaperProgress(kind = PaperProgressKind.LINEAR, label = "Проверка разрешений")
        state.error?.let { PaperText(it, color = LocalPaperColors.current.error) }
        when (state.report?.platform) {
            PermissionPlatform.MACOS -> {
                if (state.report.checks.isEmpty()) {
                    PaperText("Выберите просмотр или управление выше — здесь появятся нужные разрешения macOS.", role = PaperTextRole.LABEL)
                } else {
                    PaperText("Разрешите доступ только перечисленным программам. Перетащите карточку в список macOS и включите переключатель. Если перетаскивание недоступно, используйте «+» или Finder.", role = PaperTextRole.LABEL)
                    state.report.checks.groupBy { it.permission }.forEach { (permission, checks) ->
                        PaperPanel(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                PaperText(when (permission) {
                                    ComputerPermission.SCREEN_RECORDING -> "1. Запись экрана"
                                    ComputerPermission.ACCESSIBILITY -> "2. Универсальный доступ"
                                }, role = PaperTextRole.TITLE)
                                checks.forEach { check ->
                                    PaperText("${check.target.label} · " + when {
                                        state.busy || state.error != null -> "Нужна проверка"
                                        check.granted -> "Разрешено"
                                        else -> "Нет разрешения"
                                    })
                                }
                                PaperButton("Открыть ${if (permission == ComputerPermission.SCREEN_RECORDING) "запись экрана" else "Универсальный доступ"}",
                                    { onOpen(permission) }, enabled = !state.busy)
                            }
                        }
                    }
                    state.report.checks.map { it.target }.distinct().forEach { target ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaperFileTransfer("Перетащить: ${target.label}", target.path, { onReveal(target) }, enabled = !state.busy)
                            PaperButton("Показать в Finder: ${target.label}", { onReveal(target) }, kind = PaperButtonKind.QUIET, enabled = !state.busy)
                        }
                    }
                    PaperText("После изменения нажмите «Проверить». macOS может попросить перезапустить MagicPaper. При запуске из IDE разрешение может относиться к Java или IDE.", role = PaperTextRole.LABEL)
                }
            }
            PermissionPlatform.WINDOWS -> {
                PaperPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaperText("Отдельное разрешение Windows не требуется", role = PaperTextRole.TITLE)
                        PaperText("Откройте целевую программу в обычном режиме, без прав администратора. Окна UAC, экран блокировки и защищённое содержимое недоступны.")
                        PaperText("Управление приложением зависит от поддержки специальных возможностей самой программы. Корпоративная политика может блокировать помощник — обратитесь к администратору, не отключая защиту.", role = PaperTextRole.LABEL)
                    }
                }
            }
            PermissionPlatform.OTHER -> PaperText("Доступность зависит от платформы. Для управления используйте desktop; Linux — только X11, без фонового управления приложениями.", role = PaperTextRole.LABEL)
            null -> if (!state.busy && state.error == null) PaperText("Проверьте доступность системных разрешений.", role = PaperTextRole.LABEL)
        }
        PaperButton("Проверить", onRefresh, kind = PaperButtonKind.SECONDARY, enabled = !state.busy)
        PaperText("Проверка не делает снимков и не запускает управление. Разрешение системы не заменяет выбранный выше доступ к инструментам.", role = PaperTextRole.LABEL)
    }
}
