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

class ProjectSkillsPanel(private val repository: () -> LocalSkillRepository) : ProjectSkills {
    @Composable
    override fun Content(projectId: String) {
        key(projectId) {
            var catalogOpen by remember { mutableStateOf(false) }
            var connectKey by remember { mutableStateOf<String?>(null) }
            if (catalogOpen) {
                SkillCatalogPanel(repository, onConnect = { connectKey = it; catalogOpen = false }) { catalogOpen = false }
            } else {
            val scope = rememberCoroutineScope()
            var entries by remember { mutableStateOf<List<LocalSkillCatalogEntry>>(emptyList()) }
            var snapshot by remember { mutableStateOf(SkillReleaseSnapshot()) }
            var failure by remember { mutableStateOf("") }
            var pending by remember { mutableStateOf<Map<String, String>?>(null) }
            var permissionConsent by remember { mutableStateOf(false) }
            var trustedTextConsent by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            suspend fun refresh() { snapshot = repository().snapshot(); entries = repository().catalog() }
            fun action(block: suspend () -> Unit) {
                if (busy) return
                busy = true
                scope.launch {
                    try { block(); refresh(); failure = "" }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure = e.message ?: "Хранилище недоступно" }
                    finally { busy = false }
                }
            }
            LaunchedEffect(projectId) {
                action {
                    refresh()
                    connectKey?.let { selectedKey ->
                        val release = snapshot.installed.getValue(selectedKey)
                        require(release.status == SkillCandidateStatus.VERIFIED && release.improvement?.passed != false) {
                            "Перед подключением завершите проверку скилла."
                        }
                        val pins = snapshot.projects[projectId].orEmpty()
                        pending = pins.filterKeys { snapshot.installed[it]?.pkg?.manifest?.id != release.pkg.manifest.id } +
                            (selectedKey to release.pkg.checksum)
                        connectKey = null
                    }
                }
            }
            Column(Modifier.widthIn(max = 680.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperText("Проект: $projectId")
                PaperAction(enabled = !busy, onClick = { catalogOpen = true }) { PaperText("Добавить скилы из репозиториев") }
                PaperText(CodingSkillProtection.reason("Pi"))
                PaperText(CodingSkillProtection.reason("Codex"))
                PaperText(CodingSkillProtection.trustedTextWarning)
                PaperText("Проверка пакета не подтверждает результат задачи.")
                if (failure.isNotBlank()) PaperText(failure, color = LocalPaperColors.current.error)
                if (projectId in snapshot.previousProjects) {
                    key(projectId, snapshot.generation) {
                        ProjectSkillRollback(projectId, repository, enabled = !busy) {
                            pending = null; permissionConsent = false; trustedTextConsent = false
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
                    val requested = target.keys.flatMap { snapshot.installed.getValue(it).pkg.manifest.permissions }.toSet()
                    PaperText("Подтвердите новый состав проекта:\n" + target.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(permissionConsent, { permissionConsent = it }); PaperText("Отдельное согласие на заявленные разрешения: $requested (не выдаёт доступ)") }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(trustedTextConsent, { trustedTextConsent = it }); PaperText("Разрешаю доверенный текст для перечисленных checksum. " + CodingSkillProtection.trustedTextWarning) }
                    Row {
                        PaperAction(enabled = !busy && (requested.isEmpty() || permissionConsent), onClick = {
                            val consent = SkillActivationConsent(snapshot.generation, target, true, if (permissionConsent) requested else emptySet(), trustedCodingText = trustedTextConsent)
                            action { repository().bindProject(projectId, target, consent); pending = null }
                        }) { PaperText("Подтвердить изменение") }
                        PaperAction(onClick = { pending = null }) { PaperText("Отмена") }
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
