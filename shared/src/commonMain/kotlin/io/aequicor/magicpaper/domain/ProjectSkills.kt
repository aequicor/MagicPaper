package io.aequicor.magicpaper.domain

/** Host-owned pins and review UI; package code is never loaded. */
interface ProjectSkills {
    @androidx.compose.runtime.Composable
    fun Content(projectId: String)
}

object CodingSkillProtection {
    fun reason(adapter: String): String = when (adapter) {
        "Codex" -> "Codex: не доказаны запрет исполнения пакетного кода и изоляция нативной загрузки навыков."
        else -> "Pi: не доказаны файловая/сетевая изоляция и запрет исполнения пакетного кода через shell."
    }
}
