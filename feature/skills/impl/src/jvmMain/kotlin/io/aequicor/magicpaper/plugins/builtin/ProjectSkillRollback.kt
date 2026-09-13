package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.designsystem.*

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import io.aequicor.magicpaper.data.skills.LocalSkillRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import io.aequicor.magicpaper.logging.AppLog

private data class ProjectRollbackPreview(val consent: SkillActivationConsent, val details: String)

/** The repository owns the transaction; UI never implements rollback by rebinding or restoring opt-in. */
@Composable
internal fun ProjectSkillRollback(projectId: String, repository: () -> LocalSkillRepository, forms: SkillsFormDrafts, enabled: Boolean, onChanged: () -> Unit) {
    val operation = forms.action("rollback:$projectId", "ProjectSkillRollback", projectId)
    val operationState by operation.state.collectAsState()
    val busy = operationState.busy || operationState.cleanupPending
    val error = operationState.notice
    val owner = forms.rollback(projectId)
    val state by owner.draft.state.collectAsState()
    var preview: ProjectRollbackPreview? by owner.field(state,
        { value -> value.target?.let { ProjectRollbackPreview(SkillActivationConsent(value.generation, value.checksums, false, value.permissions), value.details) } },
        { value -> value?.let { SkillActivationForm(it.consent.targetChecksums, it.consent.generation, it.consent.targetChecksums, it.consent.permissions, it.details) } ?: SkillActivationForm() })
    var changes by owner.field(state, { it.changes }, { copy(changes = it) })
    var permissions by owner.field(state, { it.permissionConsent }, { copy(permissionConsent = it) })
    fun action(block: suspend () -> Unit) = operation.launch { block(); "" }
    if (operationState.cleanupPending) PaperAction(onClick = operation::retryCleanup) { PaperText("Повторить очистку") }
    if (state.error != null) { PaperText("Черновик не сохранён.", color = LocalPaperColors.current.error); PaperAction(onClick = { owner.draft.retry() }) { PaperText("Повторить сохранение") } }
    if (!state.loaded) { PaperProgress(); return }
    PaperAction(enabled = enabled && !busy, onClick = {
        changes = false; permissions = false
        action {
            preview = withContext(Dispatchers.IO) {
                val repo = repository()
                val snapshot = repo.snapshot()
                val target = snapshot.previousProjects[projectId] ?: error("Нет предыдущего состава")
                val current = snapshot.projects[projectId].orEmpty()
                val old = current.keys.map { snapshot.installed.getValue(it).pkg }.associateBy { it.manifest.id }
                val releases = target.map { (key, hash) -> snapshot.installed.getValue(key).also {
                    require(it.pkg.checksum == hash && it.status == SkillCandidateStatus.VERIFIED && it.improvement?.passed != false) { "Версия $key недоступна или не прошла review" }
                }.pkg }
                val added = releases.flatMap { it.manifest.permissions - old[it.manifest.id]?.manifest?.permissions.orEmpty() }.toSet()
                val details = buildString {
                    appendLine("Проект: $projectId; generation: ${snapshot.generation}")
                    appendLine("Текущий состав: $current\nПредыдущий состав: $target")
                    if (target.isEmpty()) appendLine("Будут отключены все навыки этого проекта.")
                    for (key in (current.keys + target.keys).sorted()) {
                        val pkg = snapshot.installed.getValue(key).pkg
                        val diff = repo.diff(key) // Validates local bytes; no network.
                        appendLine("${if (key in target) "ПОСЛЕ" else "ДО"}: $key\nSHA-256: ${pkg.checksum}\n${pkg.manifest}\nИсточник: ${diff.newSource}\nИнструкция:\n${diff.newInstructions}")
                    }
                    appendLine("Новые относительно текущей версии того же навыка разрешения: $added")
                }
                ProjectRollbackPreview(SkillActivationConsent(snapshot.generation, target.toMap(), false, added, trustedCodingText = false), details)
            }
        }
    }) { PaperText("Откатить состав…") }
    if (error.isNotBlank()) PaperText(error, color = LocalPaperColors.current.error)
    preview?.let { p ->
        PaperText("Предпросмотр проектного отката\n${p.details}")
        PaperText("Opt-in доверенного текста не восстанавливается. Для применения потребуется отдельное новое согласие после отката.")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(changes, { changes = it }, enabled = !busy); PaperText("Изменения отката и точные checksum просмотрены") }
        if (p.consent.permissions.isNotEmpty()) Row {
            PaperCheck(permissions, { permissions = it }, enabled = !busy)
            PaperText("Отдельно подтверждаю новые разрешения отката: ${p.consent.permissions}")
        }
        PaperAction(enabled = enabled && !busy && changes && (p.consent.permissions.isEmpty() || permissions), onClick = {
            val capturedSession = owner.draft
            val captured = capturedSession.state.value
            action {
                withContext(Dispatchers.IO) { repository().rollbackProject(projectId, captured.value.consent()) }
                if (operation.accepted { capturedSession.awaitSaved(); capturedSession.clearIfUnchanged(captured.version) }) onChanged()
            }
        }) { PaperText("Подтвердить откат без opt-in") }
        PaperAction(enabled = !busy, onClick = { val capturedSession = owner.draft
            val captured = capturedSession.state.value.version; action { owner.draft.clearIfUnchanged(captured) } }) { PaperText("Отмена отката") }
    }
}
