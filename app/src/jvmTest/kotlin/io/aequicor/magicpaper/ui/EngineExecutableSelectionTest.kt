package io.aequicor.magicpaper.ui

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.components.DefaultSubscriptionAccountPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EngineExecutableSelectionTest {
    @Test fun chosenExecutableAppliesToTheRuntimeAtOnceAndIsPersistedWithTheSettings() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ModelSettingsFixture()
        val applied = mutableListOf<Map<CodingEngine, String>>()
        val runtime = object : CodingRuntime by NoopCodingRuntime {
            override fun configureEngineExecutables(paths: Map<CodingEngine, String>) { applied += paths }
        }
        val coding = fixture.prepareCoding(runtime)
        val vm = NativeSettingsComponent(DefaultComponentContext(LifecycleRegistry()), fixture.prepareSettings(), coding,
            object : ComputerPermissions {
                override suspend fun inspect(computer: ComputerAccess, application: ComputerAccess) = ComputerPermissionReport()
                override suspend fun openSettings(permission: ComputerPermission) = error("Unexpected native action")
                override suspend fun reveal(target: PermissionTarget) = error("Unexpected native action")
            }, DefaultSubscriptionAccountPresentation, SettingsInput(SettingsPage.ENGINES), {},
            object : ExecutablePicker { override suspend fun pickExecutable() = "/opt/claude/claude.cmd" })
        try {
            vm.selectEngineExecutable(CodingEngine.CLAUDE_CODE); runCurrent()
            assertEquals(mapOf(CodingEngine.CLAUDE_CODE to "/opt/claude/claude.cmd"), applied.last(),
                "The runtime learns the choice without a restart")
            assertEquals(mapOf(CodingEngine.CLAUDE_CODE to "/opt/claude/claude.cmd"), fixture.settings.load().engineExecutables)
            assertEquals("/opt/claude/claude.cmd", vm.state.value.settings.engineExecutables[CodingEngine.CLAUDE_CODE])

            vm.clearEngineExecutable(CodingEngine.CLAUDE_CODE); runCurrent()
            assertEquals(emptyMap(), applied.last(), "Clearing returns the engine to its own discovery")
            assertEquals(emptyMap(), fixture.settings.load().engineExecutables)
        } finally { coding.close(); Dispatchers.resetMain() }
    }
}
