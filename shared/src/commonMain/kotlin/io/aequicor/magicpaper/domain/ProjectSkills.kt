package io.aequicor.magicpaper.domain

/** Host-owned pins and review UI; package code is never loaded. */
interface ProjectSkills {
    @androidx.compose.runtime.Composable
    fun Content(projectId: String)
}

data class CodingSkillSelection(
    val instructions: List<SkillInstruction>,
    val trustedText: Boolean = false,
    val freshSession: Boolean = false,
)

/** Host-owned prepared snapshot; deliberately excludes task, history, attachments and output. */
data class CodingSkillRunRecord(
    val runId: String,
    val projectId: String,
    val sessionId: String,
    val adapter: String,
    val selection: CodingSkillSelection,
)

object CodingSkillProtection {
    const val trustedTextWarning = "Доверенный текст: агент может выполнить команды из SKILL.md с обычными полномочиями backend. Разрешения пакета — декларация, не ACL. Приложение не запускает установщики/плагины. Каждый запуск этого проекта, в том числе после отключения, начинает новую engine-сессию без старой истории."

    fun reason(adapter: String): String = when (adapter) {
        "Codex" -> "Codex: не доказаны запрет исполнения пакетного кода и изоляция нативной загрузки навыков."
        else -> "Pi: не доказаны файловая/сетевая изоляция и запрет исполнения пакетного кода через shell."
    }
}
