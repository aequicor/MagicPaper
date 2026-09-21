package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.coding.backendProtocols
import io.aequicor.magicpaper.data.coding.backendCatalog
import io.aequicor.magicpaper.ui.components.SubscriptionAccountPresentation
import io.aequicor.magicpaper.ui.screens.ComputerSettings
import io.aequicor.magicpaper.ui.screens.EnginesSettings
import kotlinx.coroutines.*

/** Native controls live with the native service; a settings visit never owns execution. */
class NativeSettingsComponent(
    context: ComponentContext,
    private val service: SettingsService,
    val coding: CodingService,
    permissions: ComputerPermissions,
    val subscription: SubscriptionAccountPresentation,
    private val input: SettingsInput,
    private val onOutput: (SettingsOutput) -> Unit,
) : SettingsComponent, SettingsService by service {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val engines = backendCatalog.descriptors
    internal val computerPermissions = ComputerPermissionController(permissions, scope)
    init {
        require(input.page == SettingsPage.ENGINES || input.page == SettingsPage.COMPUTER)
        context.lifecycle.doOnDestroy { scope.cancel() }
    }
    internal fun saveComputerAccess(settings: AppSettings) = saveComputerAccess(settings.computerAccess, settings.applicationAccess)
    fun closeEnginesSettings() = onOutput(SettingsOutput.Overview)
    fun closeComputerSettings() = onOutput(SettingsOutput.Overview)
    fun openComputerSettings() = onOutput(SettingsOutput.Computer)
    fun openModelsSettings() = onOutput(SettingsOutput.Models)
    fun prepareCodingRuntime(engine: CodingEngine) = coding.prepareCodingRuntime(engine)
    fun uninstallCodingRuntime(engine: CodingEngine) = coding.uninstallCodingRuntime(engine)
    override fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.Save -> service.saveSettings(action.settings)
            is SettingsAction.SaveProfile -> service.saveLlmProfile(action.profile)
        }
    }
    @Composable override fun Content() {
        val current by state.collectAsState()
        when (input.page) {
            SettingsPage.ENGINES -> EnginesSettings(this, current)
            SettingsPage.COMPUTER -> ComputerSettings(this, current)
            else -> error("Native settings factory received an unregistered page")
        }
    }
}

class NativeSettingsComponentFactory(
    private val service: SettingsService,
    private val coding: CodingService,
    private val permissions: ComputerPermissions,
    private val subscription: SubscriptionAccountPresentation,
) : SettingsComponent.Factory {
    override fun create(context: ComponentContext, input: SettingsInput, onOutput: (SettingsOutput) -> Unit): SettingsComponent =
        NativeSettingsComponent(context, service, coding, permissions, subscription, input, onOutput)
}

fun nativeSettingsContributions(factory: SettingsComponent.Factory): SettingsContributions = SettingsContributions(
    pages = listOf(
        SettingsPageRegistration(SettingsPage.ENGINES,
            SettingsNavigationEntry("⚙", "Движки", "Движок новых сессий и подготовка", SettingsOutput.Engines), factory),
        SettingsPageRegistration(SettingsPage.COMPUTER,
            SettingsNavigationEntry("▣", "Управление компьютером", "Доступ к экрану, приложениям и разрешения системы", SettingsOutput.Computer), factory),
    ),
    navigation = listOf(SettingsNavigationEntry("⌘", "Проекты и код", "Кодинг-агент работает в папке проекта", SettingsOutput.Projects)),
)
