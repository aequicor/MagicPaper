package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.designsystem.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.skills.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.aequicor.magicpaper.logging.AppLog

internal class ProjectSkillsPanel(private val forms: SkillsFormDrafts, private val repository: () -> LocalSkillRepository) : ProjectSkills {
    @Composable
    override fun Content(projectId: String) {
        val lifecycle by forms.lifecycle.collectAsState()
        key(projectId, lifecycle) {
            if (!forms.available(projectId)) { PaperText("Проект удалён.") } else {
            val owner = forms.project(projectId)
            val state by owner.draft.state.collectAsState()
            if (state.error != null) { PaperText("Черновик не сохранён.", color = LocalPaperColors.current.error); PaperAction(onClick = { owner.draft.retry() }) { PaperText("Повторить сохранение") } }
            if (!state.loaded) { PaperProgress(Modifier.fillMaxWidth()) } else {
            var catalogOpen by owner.field(state, { it.catalogOpen }, { copy(catalogOpen = it) })
            var connectKey by owner.field(state, { it.connectKey }, { copy(connectKey = it) })
            if (catalogOpen) {
                SkillCatalogPanel(repository, forms, projectId, onConnect = { connectKey = it; catalogOpen = false }) { catalogOpen = false }
            } else {
            val operation = forms.action("project:$projectId", "ProjectSkillsPanel", projectId)
            val operationState by operation.state.collectAsState()
            val busy = operationState.busy || operationState.cleanupPending
            var failure by operation.notice(operationState)
            var entries by remember { mutableStateOf<List<LocalSkillCatalogEntry>>(emptyList()) }
            var snapshot by remember { mutableStateOf(SkillReleaseSnapshot()) }
            var pending by owner.field(state, { it.pending }, { copy(pending = it, generation = snapshot.generation, permissionConsent = false, trustedTextConsent = false) })
            var permissionConsent by owner.field(state, { it.permissionConsent }, { copy(permissionConsent = it) })
            var trustedTextConsent by owner.field(state, { it.trustedTextConsent }, { copy(trustedTextConsent = it) })
            suspend fun refresh() { val fresh = withContext(Dispatchers.IO) { repository().snapshot() to repository().catalog() }; snapshot = fresh.first; entries = fresh.second }
            fun action(block: suspend () -> Unit) = operation.launch { block(); "" }
            LaunchedEffect(projectId, busy) {
                if (!busy) try {
                    refresh()
                    connectKey?.let { selectedKey ->
                        val release = snapshot.installed.getValue(selectedKey)
                        require(release.status == SkillCandidateStatus.VERIFIED && release.improvement?.passed != false) {
                            "Перед подключением завершите проверку скилла."
                        }
                        val pins = snapshot.projects[projectId].orEmpty()
                        pending = pins.filterKeys { snapshot.installed[it]?.pkg?.manifest?.id != release.pkg.manifest.id } +
                            (selectedKey to release.pkg.checksum)
                        permissionConsent = false; trustedTextConsent = false
                        connectKey = null
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { operation.report(failure) }
            }
            Column(Modifier.widthIn(max = 680.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperText("Проект: $projectId")
                PaperAction(enabled = !busy, onClick = { catalogOpen = true }) { PaperText("Добавить скилы из репозиториев") }
                PaperText(CodingSkillProtection.reason("Pi"))
                PaperText(CodingSkillProtection.reason("Codex"))
                PaperText(CodingSkillProtection.trustedTextWarning)
                PaperText("Проверка пакета не подтверждает результат задачи.")
                if (failure.isNotBlank()) PaperText(failure, color = LocalPaperColors.current.error)
                if (operationState.cleanupPending) PaperAction(onClick = operation::retryCleanup) { PaperText("Повторить очистку") }
                if (projectId in snapshot.previousProjects) {
                    key(projectId, snapshot.generation) {
                        val capturedProjectVersion = state.version
                        ProjectSkillRollback(projectId, repository, forms, enabled = !busy) {
                            if (owner.draft.state.value.version == capturedProjectVersion) owner.update { it.copy(pending = null, permissionConsent = false, trustedTextConsent = false) }
                            action { }
                        }
                    }
                }
                val pins = snapshot.projects[projectId].orEmpty()
                val trusted = pins.isNotEmpty() && snapshot.projectTextConsents[projectId] == pins
                PaperText(if (trusted) "Режим: доверенный текст. Фактические инструменты: штатная политика backend; отдельной ACL пакета нет." else "Применение подключённых пакетов заблокировано: нет согласия на доверенный текст точного состава.")
                if (projectId in snapshot.projectTextConsents) PaperText("Новая engine-сессия на каждом запуске; прежняя история не передаётся.")
                PaperAction(enabled = !busy && pins.isNotEmpty(), onClick = { pending = pins; permissionConsent = false; trustedTextConsent = false }) { PaperText("Настроить доверенный текст…") }
                pending?.let { target ->
                    val valid = state.value.matches(snapshot)
                    val requested = target.keys.flatMap { snapshot.installed[it]?.pkg?.manifest?.permissions.orEmpty() }.toSet()
                    if (!valid) PaperText("Состав библиотеки изменился. Выберите навыки заново.", color = LocalPaperColors.current.error)
                    PaperText("Подтвердите новый состав проекта:\n" + target.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(permissionConsent, { permissionConsent = it }); PaperText("Отдельное согласие на заявленные разрешения: $requested (не выдаёт доступ)") }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(trustedTextConsent, { trustedTextConsent = it }); PaperText("Разрешаю доверенный текст для перечисленных checksum. " + CodingSkillProtection.trustedTextWarning) }
                    Row {
                        PaperAction(enabled = !busy && valid && (requested.isEmpty() || permissionConsent), onClick = {
                            val capturedSession = owner.draft
                            val captured = capturedSession.state.value
                            val consent = SkillActivationConsent(captured.value.generation, target, true, if (captured.value.permissionConsent) requested else emptySet(), trustedCodingText = captured.value.trustedTextConsent)
                            action { withContext(Dispatchers.IO) { repository().bindProject(projectId, target, consent) }; operation.accepted { capturedSession.awaitSaved(); capturedSession.clearIfUnchanged(captured.version) } }
                        }) { PaperText("Подтвердить изменение") }
                        PaperAction(onClick = { val captured = owner.draft.state.value.version; action { owner.draft.clearIfUnchanged(captured) } }) { PaperText("Отмена") }
                    }
                }
                for (section in listOf("Подключённые", "Созданы автоматически", "Библиотека")) {
                    PaperText(section, style = LocalPaperTypography.current.title)
                    val items = entries.filter { when (section) {
                        "Подключённые" -> it.release.pkg.key in pins
                        "Созданы автоматически" -> it.release.improvement != null
                        else -> it.release.pkg.key !in pins && it.release.improvement == null
                    } }
                    if (items.isEmpty()) PaperText("Нет навыков")
                    items.forEach { entry ->
                        val r = entry.release
                        val m = r.pkg.manifest
                        PaperText("${m.name} · ${r.pkg.key}\nSHA-256: ${r.pkg.checksum}\nИсточник импорта: ${entry.source.location}\nЗаявлен: ${m.origin}\nЛицензия: ${m.license ?: "не указана"}\nПроверка: ${r.status}; ${r.review?.evidence.orEmpty()}\nЗаявленные разрешения: ${m.permissions}\nПроверки улучшения: ${r.improvement ?: "не применимо"}")
                        val connected = r.pkg.key in pins
                        val eligible = r.status == SkillCandidateStatus.VERIFIED && r.improvement?.passed != false
                        if (!eligible) PaperText("Блокировка: карантин или не пройдены проверки улучшения. Review доступен в «Пакеты навыков».")
                        PaperAction(enabled = !busy && (connected || eligible), onClick = {
                            val proposed = pending ?: pins
                            pending = if (r.pkg.key in proposed) proposed - r.pkg.key else proposed.filterKeys { key -> snapshot.installed[key]?.pkg?.manifest?.id != m.id } + (r.pkg.key to r.pkg.checksum)
                            permissionConsent = false
                            trustedTextConsent = false
                        }) { PaperText(if (connected) "Отключить…" else "Подключить скилл") }
                    }
                }

            }
            }
            }
            }
        }
    }
}
