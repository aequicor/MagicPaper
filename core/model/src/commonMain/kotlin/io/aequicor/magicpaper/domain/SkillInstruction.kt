package io.aequicor.magicpaper.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Only the host repository may supply reviewed active releases; no execution capability here. */
fun interface SkillInstructionSource {
    suspend fun active(): List<SkillInstruction>
}

data class SkillInstruction(
    val id: String,
    val version: String,
    val checksum: String,
    val name: String,
    val description: String,
    val permissions: Set<SkillPermission>,
    val text: String,
)
