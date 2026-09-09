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
import kotlinx.coroutines.*

@Composable
internal fun SkillCatalogPanel(
    repository: () -> LocalSkillRepository,
    catalogFactory: () -> GithubSkillCatalog = { GithubSkillCatalog() },
    onConnect: ((String) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val catalog = remember { catalogFactory() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var network by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var discovery by remember { mutableStateOf<GithubSkillCatalog.Discovery?>(null) }
    var preview by remember { mutableStateOf<GithubSkillCatalog.Preview?>(null) }
    var local by remember { mutableStateOf<List<LocalSkillCatalogEntry>>(emptyList()) }
    var review by remember { mutableStateOf<LocalSkillCatalogEntry?>(null) }
    var details by remember { mutableStateOf("") }
    var evidence by remember { mutableStateOf("") }
    var origin by remember { mutableStateOf(false) }
    var license by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        job = scope.launch {
            try { block(); local = withContext(Dispatchers.IO) { repository().catalog() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Ошибка загрузки или хранилища" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { action { } }
    Column(Modifier.widthIn(max = 680.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Добавить скилы из репозиториев", style = MaterialTheme.typography.titleLarge)
        Text("Поиск локальный. Сеть — только по подтверждению. Импорт не подключает пакет, не активирует и не даёт opt-in coding. Ресурсы не исполняются.")
        TextButton(onClick = { job?.cancel(); onClose() }) { Text("Назад к проекту") }
        if (notice.isNotEmpty()) Text(notice)
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { job?.cancel(); notice = "Отменено. Незавершённая загрузка не устанавливается; завершённый атомарный импорт сохраняется." }) { Text("Отменить загрузку") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Поиск навыков без сети") }, modifier = Modifier.fillMaxWidth())
        Text("Проверенные публичные источники")
        val sources = catalog.search(query)
        if (sources.isEmpty()) Text("Источники не найдены. Введите ссылку ниже.")
        sources.forEach { source ->
            Text(source.name + "\n" + source.description + "\n" + source.url)
            TextButton(enabled = !busy, onClick = { link = source.url; network = false; discovery = null; preview = null }) { Text("Выбрать источник") }
        }
        OutlinedTextField(link, { link = it; network = false; discovery = null; preview = null }, enabled = !busy,
            label = { Text("Ссылка GitHub: репозиторий или tree/blob/ref/путь") }, modifier = Modifier.fillMaxWidth())
        Row { Checkbox(network, { network = it }, enabled = !busy); Text("Разрешаю анонимные GET к github.com, api.github.com, codeload.github.com. Передаётся только адрес источника, без рабочих данных и ключей.") }
        TextButton(enabled = !busy && network && link.isNotBlank(), onClick = {
            action { discovery = null; preview = null; discovery = catalog.discover(link, network); notice = "Выберите один пакет. Полный SHA зафиксирован." }
        }) { Text("Найти пакеты по ссылке") }
        discovery?.let { d ->
            Text("Источник: ${d.repository}\nCommit: ${d.commit}")
            d.packages.forEach { path -> TextButton(enabled = !busy, onClick = {
                action {
                    val p = withContext(Dispatchers.IO) { catalog.preview(d, path) }
                    preview = p
                    val old = local.filter { it.release.pkg.manifest.id == p.manifest.id }
                    details = buildString {
                        for (entry in old) {
                            val previous = withContext(Dispatchers.IO) { repository().diff(entry.release.pkg.key) }
                            appendLine("До: ${entry.release.pkg.manifest}\nChecksum: ${entry.release.pkg.checksum}\n${previous.newInstructions}")
                            val a = entry.release.pkg.manifest.files.associateBy { it.path }
                            val b = p.manifest.files.associateBy { it.path }
                            appendLine("Добавлены: ${b.keys - a.keys}; удалены: ${a.keys - b.keys}; изменены: ${(a.keys intersect b.keys).filter { a[it] != b[it] }}")
                        }
                    }
                }
            }) { Text(path) } }
        }
        preview?.let { p ->
            Text("Предпросмотр / сравнение")
            Text(details)
            Text("После (метаданные адаптера, не версия издателя): ${p.manifest}\nSHA-256: ${p.checksum}\nФактический источник: ${p.source}")
            Text("Лицензия: ${p.manifest.license ?: "не установлена — подключение блокируется до обоснованного review"}")
            Text("Исходный SKILL.md, включая метаданные автора:\n${p.original}")
            Text("Лицензионный файл репозитория (применимость не подтверждена):\n${p.licenseEvidence ?: "не найден"}")
            TextButton(enabled = !busy, onClick = {
                action { withContext(Dispatchers.IO) { catalog.install(repository(), p) }; preview = null; notice = "Сохранено. Новый пакет в карантине; идентичный повтор не создаёт дубль. Далее — отдельный review точного checksum." }
            }) { Text("Импортировать без подключения") }
            TextButton(enabled = !busy, onClick = { preview = null }) { Text("Отменить предпросмотр") }
        }
        HorizontalDivider()
        Text("Установленные — доступны офлайн")
        val matches = local.filter { (it.release.pkg.manifest.toString() + it.source).contains(query.trim(), true) }
        if (matches.isEmpty()) Text("Пакеты не найдены")
        matches.forEach { entry ->
            val p = entry.release.pkg
            Text("${p.manifest.name} · ${p.key}\n${entry.source}\nChecksum: ${p.checksum}\nЛицензия: ${p.manifest.license ?: "не установлена"}; ${entry.release.status}")
            if (onConnect != null) {
                val eligible = entry.release.status == SkillCandidateStatus.VERIFIED && entry.release.improvement?.passed != false
                Button(enabled = !busy && eligible, onClick = { onConnect(p.key) }) { Text("Подключить скилл") }
                if (!eligible) Text("Для подключения завершите проверку скилла через «Просмотр и review».")
            }
            TextButton(enabled = !busy, onClick = {
                action {
                    val diff = withContext(Dispatchers.IO) { repository().diff(p.key) }
                    preview = null; review = entry; evidence = ""; origin = false; license = false; content = false
                    details = "${diff.after}\n${diff.newInstructions}\n" + diff.resources.joinToString("\n") { it.toString() }
                }
            }) { Text("Просмотр и review") }
            if (entry.source.kind == SkillImportKind.GIT) TextButton(enabled = !busy, onClick = {
                val url = entry.source.location.substringBefore("/tree/")
                link = url; network = false; discovery = null; preview = null
                notice = "Для проверки обновления подтвердите сеть. Будет разрешён новый полный SHA; проектная версия не меняется."
            }) { Text("Проверить обновление…") }
        }
        review?.let { entry ->
            Text("Review точного SHA-256: ${entry.release.pkg.checksum}\n$details")
            OutlinedTextField(evidence, { evidence = it }, label = { Text("Обоснование происхождения, лицензии и проверки ресурсов") })
            Row { Checkbox(origin, { origin = it }); Text("Происхождение установлено") }
            Row { Checkbox(license, { license = it }); Text("Лицензия и её применимость установлены") }
            Row { Checkbox(content, { content = it }); Text("Все инструкции и ресурсы проверены") }
            TextButton(enabled = !busy && evidence.isNotBlank(), onClick = { action {
                withContext(Dispatchers.IO) { repository().review(entry.release.pkg.key, SkillPackageReview(entry.release.pkg.checksum, "local-user", evidence, origin, license, content)) }
                review = null; notice = "Review сохранён. Подключение и согласие на coding — отдельно в проектной панели."
            } }) { Text("Сохранить review без подключения") }
            TextButton(onClick = { review = null }) { Text("Отмена review") }
        }
    }
}
