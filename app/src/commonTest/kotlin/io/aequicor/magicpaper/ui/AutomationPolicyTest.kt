package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationPolicyTest {
    private class Computer : ComputerUse {
        override val supported = true
        override val applicationSupported = true
        override val state = MutableStateFlow(ComputerUseState())
        var policy = ComputerAccess.OFF to ComputerAccess.OFF
        override fun configure(computer: ComputerAccess, application: ComputerAccess) {
            if (policy != computer to application) { policy = computer to application; disable() }
        }
        override suspend fun enable(sessionId: String, access: ComputerAccess) { state.value = ComputerUseState(sessionId, access) }
        override fun disable(sessionId: String?) { state.value = ComputerUseState() }
        override suspend fun preview(sessionId: String) { error("Must not capture") }
        override fun openSystemSettings() { error("Must not launch settings") }
    }

    @Test fun loadingNeverGrantsAndSaveRevokesBeforeSuspensionWithoutEchoRegrant() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val on = f.settings.load().copy(computerAccess = ComputerAccess.CONTROL, applicationAccess = ComputerAccess.SCREEN)
        f.settings.save(on)
        val computer = Computer()
        val backend = object : CodingRuntime by NoopCodingRuntime { override val computerUse = computer }
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        val repository = object : SettingsRepository by f.settings {
            override suspend fun save(settings: AppSettings) {
                gate?.await()
                check(!fail) { "fixture storage failure" }
                f.settings.save(settings)
            }
        }
        val service = DefaultCodingService(repository, f.profiles, f.kv, f.json, backend, usage = f.usage, workerDispatcher = Dispatchers.Main)
        try {
            service.start(); runCurrent()
            assertEquals(ComputerAccess.CONTROL to ComputerAccess.SCREEN, computer.policy)
            assertNull(computer.state.value.sessionId)
            computer.enable("s", ComputerAccess.CONTROL)
            gate = CompletableDeferred()
            val off = on.copy(computerAccess = ComputerAccess.OFF, applicationAccess = ComputerAccess.OFF)
            val saving = async { service.applySettings(off) }; runCurrent()
            assertNull(computer.state.value.sessionId)
            assertEquals(ComputerAccess.OFF to ComputerAccess.OFF, computer.policy)
            service.updateConfiguration(on, emptyList(), false, false)
            assertEquals(ComputerAccess.OFF to ComputerAccess.OFF, computer.policy, "Old settings observer cannot restore revoked policy")
            gate!!.complete(Unit); saving.await().getOrThrow()
            fail = true
            assertFailsWith<IllegalStateException> { service.applySettings(on) }
            assertEquals(off, f.settings.load())
            assertEquals(ComputerAccess.OFF to ComputerAccess.OFF, computer.policy)
        } finally { gate?.complete(Unit); service.close(); Dispatchers.resetMain() }
    }

    @Test fun profileExportAndImportCannotTransferAuthorizationAndImportFailuresAreVisible() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture(); f.seed()
        val on = f.settings.load().copy(computerAccess = ComputerAccess.CONTROL, applicationAccess = ComputerAccess.CONTROL)
        f.settings.save(on)
        var exported = ""
        val bridge = object : ProfileBridge {
            override val supportsFilePicker = true
            override suspend fun export(json: String): Boolean { exported = json; return true }
            override suspend fun import() = f.json.encodeToString(ProfileBundle.serializer(), ProfileBundle(
                exportedAt = 0, settings = on, plugins = emptyList(), sessions = emptyList()))
        }
        var fail = false
        val applied = mutableListOf<AppSettings>()
        val service = DefaultSettingsService(f.settings, f.profiles, f.chats, bridge, f.kv, f.json, usage = f.usage,
            applyRuntimeSettings = { settings ->
                applied += settings
                if (fail) error("fixture storage failure")
                f.settings.save(settings); Result.success(Unit)
            })
        try {
            service.start()
            service.exportProfile(); runCurrent()
            val bundle = f.json.decodeFromString(ProfileBundle.serializer(), exported)
            assertEquals(ComputerAccess.OFF, bundle.settings.computerAccess)
            assertEquals(ComputerAccess.OFF, bundle.settings.applicationAccess)
            assertEquals(on, f.settings.load(), "Export must not change local policy")
            service.importProfile(); service.state.first { !it.settingsSaving }
            assertEquals(ComputerAccess.OFF, applied.single().computerAccess)
            assertEquals(ComputerAccess.OFF, applied.single().applicationAccess)
            assertEquals("Профиль импортирован.", service.state.value.notice)
            fail = true
            service.importProfile(); service.state.first { !it.settingsSaving }
            assertContains(service.state.value.notice.orEmpty(), "Не удалось завершить импорт")
            assertFalse(service.state.value.settingsSaving)
        } finally { service.close(); Dispatchers.resetMain() }
    }
}
