package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingRuntime
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProjectDirPicker
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Заглушка для платформ без кодинг-бэкенда (веб, Android). */
object NoopCodingRuntime : CodingRuntime {
    override val supported: Boolean = false
    override val rootPath: String = ""

    private val status = RuntimeStatus(
        phase = RuntimePhase.UNSUPPORTED,
        detail = "Кодинг-агент доступен только в десктопной версии.",
    )

    override suspend fun status(): RuntimeStatus = status
    override fun ensureReady(): Flow<RuntimeStatus> = flowOf(status)
    override fun run(project: CodingProject, prompt: String, profile: LlmProfile?): Flow<CodingEvent> =
        flowOf(CodingEvent.Failed("Кодинг-агент не поддерживается на этой платформе."))

    override suspend fun uninstall() = Unit

    override fun abort() = Unit
}

object NoopProjectDirPicker : ProjectDirPicker {
    override suspend fun pickDirectory(): String? = null
}
