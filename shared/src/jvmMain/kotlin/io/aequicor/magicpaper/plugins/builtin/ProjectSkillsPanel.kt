package io.aequicor.magicpaper.plugins.builtin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
            val scope = rememberCoroutineScope()
            var entries by remember { mutableStateOf<List<LocalSkillCatalogEntry>>(emptyList()) }
            var snapshot by remember { mutableStateOf(SkillReleaseSnapshot()) }
            var failure by remember { mutableStateOf("") }
            var pending by remember { mutableStateOf<Map<String, String>?>(null) }
            var permissionConsent by remember { mutableStateOf(false) }
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
            LaunchedEffect(projectId) { action { } }
            Column(Modifier.widthIn(max = 680.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Проект: $projectId")
                Text(CodingSkillProtection.reason("Pi"))
                Text(CodingSkillProtection.reason("Codex"))
                Text("Предоставлено пакету: нет. Подключение не выдаёт полномочий. Проверка пакета не подтверждает результат задачи.")
                if (failure.isNotBlank()) Text(failure, color = MaterialTheme.colorScheme.error)
                val pins = snapshot.projects[projectId].orEmpty()
                for (section in listOf("Подключённые", "Созданы автоматически", "Библиотека")) {
                    Text(section, style = MaterialTheme.typography.titleMedium)
                    val items = entries.filter { when (section) {
                        "Подключённые" -> it.release.pkg.key in pins
                        "Созданы автоматически" -> it.release.improvement != null
                        else -> it.release.pkg.key !in pins && it.release.improvement == null
                    } }
                    if (items.isEmpty()) Text("Нет навыков")
                    items.forEach { entry ->
                        val r = entry.release
                        val m = r.pkg.manifest
                        Text("${m.name} · ${r.pkg.key}\nSHA-256: ${r.pkg.checksum}\nИсточник импорта: ${entry.source.location}\nЗаявлен: ${m.origin}\nЛицензия: ${m.license ?: "не указана"}\nПроверка: ${r.status}; ${r.review?.evidence.orEmpty()}\nЗаявленные разрешения: ${m.permissions}\nПроверки улучшения: ${r.improvement ?: "не применимо"}")
                        val connected = r.pkg.key in pins
                        val eligible = r.status == SkillCandidateStatus.VERIFIED && r.improvement?.passed != false
                        if (!eligible) Text("Блокировка: карантин или не пройдены проверки улучшения. Review доступен в «Пакеты навыков».")
                        TextButton(enabled = !busy && (connected || eligible), onClick = {
                            val proposed = pending ?: pins
                            pending = if (r.pkg.key in proposed) proposed - r.pkg.key else proposed.filterKeys { key -> snapshot.installed[key]?.pkg?.manifest?.id != m.id } + (r.pkg.key to r.pkg.checksum)
                            permissionConsent = false
                        }) { Text(if (connected) "Отключить…" else "Подключить / обновить…") }
                    }
                }
                pending?.let { target ->
                    val requested = target.keys.flatMap { snapshot.installed.getValue(it).pkg.manifest.permissions }.toSet()
                    Text("Подтвердите новый состав проекта:\n" + target.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                    Row { Checkbox(permissionConsent, { permissionConsent = it }); Text("Отдельное согласие на заявленные разрешения: $requested (не выдаёт доступ)") }
                    Row {
                        TextButton(enabled = !busy && (requested.isEmpty() || permissionConsent), onClick = {
                            val consent = SkillActivationConsent(snapshot.generation, target, true, if (permissionConsent) requested else emptySet())
                            action { repository().bindProject(projectId, target, consent); pending = null }
                        }) { Text("Подтвердить изменение") }
                        TextButton(onClick = { pending = null }) { Text("Отмена") }
                    }
                }
            }
        }
    }
}
