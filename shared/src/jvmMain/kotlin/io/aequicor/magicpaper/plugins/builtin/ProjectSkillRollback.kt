package io.aequicor.magicpaper.plugins.builtin

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.aequicor.magicpaper.data.skills.LocalSkillRepository
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*

private data class ProjectRollbackPreview(val consent: SkillActivationConsent, val details: String)

/** The repository owns the transaction; UI never implements rollback by rebinding or restoring opt-in. */
@Composable
internal fun ProjectSkillRollback(projectId: String, repository: () -> LocalSkillRepository, enabled: Boolean, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var preview by remember(projectId) { mutableStateOf<ProjectRollbackPreview?>(null) }
    var changes by remember(projectId) { mutableStateOf(false) }
    var permissions by remember(projectId) { mutableStateOf(false) }
    var busy by remember(projectId) { mutableStateOf(false) }
    var error by remember(projectId) { mutableStateOf("") }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block(); error = "" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                preview = null; changes = false; permissions = false
                error = "Откат не выполнен: ${e.message}. Откройте новый предпросмотр; прежнее согласие не используется."
            } finally { busy = false }
        }
    }
    TextButton(enabled = enabled && !busy, onClick = {
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
    }) { Text("Откатить состав…") }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    preview?.let { p ->
        Text("Предпросмотр проектного отката\n${p.details}")
        Text("Opt-in доверенного текста не восстанавливается. Для применения потребуется отдельное новое согласие после отката.")
        Row { Checkbox(changes, { changes = it }, enabled = !busy); Text("Изменения отката и точные checksum просмотрены") }
        if (p.consent.permissions.isNotEmpty()) Row {
            Checkbox(permissions, { permissions = it }, enabled = !busy)
            Text("Отдельно подтверждаю новые разрешения отката: ${p.consent.permissions}")
        }
        TextButton(enabled = enabled && !busy && changes && (p.consent.permissions.isEmpty() || permissions), onClick = {
            action {
                withContext(Dispatchers.IO) {
                    repository().rollbackProject(projectId, p.consent.copy(reviewedChanges = changes,
                        permissions = if (permissions) p.consent.permissions else emptySet(), trustedCodingText = false))
                }
                preview = null; changes = false; permissions = false; onChanged()
            }
        }) { Text("Подтвердить откат без opt-in") }
        TextButton(enabled = !busy, onClick = { preview = null; changes = false; permissions = false }) { Text("Отмена отката") }
    }
}
