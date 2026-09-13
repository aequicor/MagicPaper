package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

object UnavailablePlanningGateway : PlanningGateway {
    override suspend fun completeWithActivity(project: CodingProject, engine: CodingEngine, requestId: String,
        profile: LlmProfile, messages: List<LlmMessage>, onActivity: (CodingStep) -> Unit): String =
        error("Чтение проекта при планировании недоступно на этой платформе. Откройте проект в desktop-приложении.")
}