package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface SkillRepository {
    suspend fun all(): List<Skill>
    suspend fun save(skill: Skill)
    suspend fun delete(id: String)
    suspend fun wipe()
}