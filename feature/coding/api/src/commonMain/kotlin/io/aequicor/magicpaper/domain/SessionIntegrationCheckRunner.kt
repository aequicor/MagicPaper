package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface SessionIntegrationCheckRunner {
    suspend fun run(path: String, id: String, command: List<String>): SessionIntegrationCheck
    fun abort(id: String)
    suspend fun reconcile(id: String)
}