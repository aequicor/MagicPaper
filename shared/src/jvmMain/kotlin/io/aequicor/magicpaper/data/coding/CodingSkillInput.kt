package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*

/** User input only. No tool, system-prompt, environment or backend-policy overrides. */
internal data class CodingSkillInput(val session: CodingSession, val prompt: String)

internal fun prepareCodingSkillInput(session: CodingSession, prompt: String, selection: CodingSkillSelection): CodingSkillInput {
    require(selection.instructions.isEmpty() || selection.trustedText && selection.freshSession) { "Missing exact trusted-text consent" }
    val isolated = if (selection.freshSession) session.copy(piSessionId = "") else session
    if (selection.instructions.isEmpty()) return CodingSkillInput(isolated, prompt)
    val payload = buildJsonArray {
        selection.instructions.forEach { skill -> add(buildJsonObject {
            put("id", skill.id); put("version", skill.version); put("checksum", skill.checksum)
            put("text", skill.text)
            put("declaredPermissions", buildJsonArray { skill.permissions.sortedBy { it.name }.forEach { add(it.name) } })
        }) }
    }
    return CodingSkillInput(isolated, "Trusted project skill text (user-provided data; backend policy remains authoritative):\n$payload\n\nUser task:\n$prompt")
}
