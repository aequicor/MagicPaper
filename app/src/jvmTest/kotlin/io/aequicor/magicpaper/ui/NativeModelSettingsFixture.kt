package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.NoopFilePicker
import io.aequicor.magicpaper.data.coding.journalCodingProjects
import kotlinx.coroutines.launch
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume

suspend fun ModelSettingsFixture.prepareCoding(codingRuntime: CodingRuntime? = null, codingProjects: CodingProjectRepository? = null,
        requestPinRepository: RequestPinRepository? = null,
        workerDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main,
        activate: Boolean = true, taskWorktrees: TaskWorktreeService? = null, planningChat: PlanningChatService? = null,
        settingsCommands: SettingsCommands = testSettingsCommands()): DefaultCodingService {
        seed()
        val owner = when (codingProjects) {
            null -> null
            is CodingProjectOwner -> codingProjects
            is CodingCheckpointStore -> journalCodingProjects(kv, json, chatJournal, workerDispatcher, codingProjects)
            else -> error("The fixture must supply a journal owner or an explicit legacy checkpoint cache")
        }
        lateinit var service: DefaultCodingService
        service = DefaultCodingService(settings, profiles, kv, json, codingRuntime, owner,
            gateway = gateway, requestPins = pins(requestPinRepository), usage = usage, workerDispatcher = workerDispatcher,
            settingsCommands = settingsCommands,
            draftRepository = draftRepository, draftBlobs = draftBlobs, taskWorktrees = taskWorktrees, planningChat = planningChat,
            onOpenSession = { project, session -> scope.launch { service.activate(project, session) } })
        service.start()
        if (activate) {
            val project = service.state.value.coding.projects.firstOrNull()
            service.activate(project?.id, service.state.value.coding.sessions.firstOrNull { it.session.projectId == project?.id }?.session?.id)
        }
        return service
    }

fun ModelSettingsFixture.prepareCodingComponent(service: DefaultCodingService, input: CodingInput = CodingInput(
        service.state.value.coding.currentSession?.session?.projectId, service.state.value.coding.currentSession?.session?.id)): DefaultCodingComponent {
        val lifecycle = LifecycleRegistry().apply { resume() }
        return DefaultCodingComponentFactory(service, NoopFilePicker)
            .create(DefaultComponentContext(lifecycle), input) {} as DefaultCodingComponent
    }

/** Native fixtures use the same durable configuration owner as Settings. */
fun ModelSettingsFixture.testSettingsCommands(): SettingsCommands = configuration
