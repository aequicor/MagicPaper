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
import io.aequicor.magicpaper.plugins.MagicPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Package management is separate from legacy skill drafts: importing never enables instructions. */
class LocalSkillsPlugin(private val root: Path) : MagicPlugin, AutoCloseable {
    override val id = "local-skill-packages"
    override val title = "Пакеты навыков"
    override val description = "Локальные версии, импорт, проверка и восстановление навыков."
    override val icon = "▤"
    private val host = SkillPackageHost("1.0.0", "desktop")
    private var repository: LocalSkillRepository? = null
    @Synchronized
    internal fun repo(): LocalSkillRepository = repository ?: LocalSkillRepository(root, host, allowRecovery = true).also { repository = it }
    internal var beforeInstructions: suspend () -> Unit = {}
    val instructionSource = SkillInstructionSource { withContext(Dispatchers.IO) { beforeInstructions(); repo().active() } }
    @Synchronized
    override fun close() { repository?.close(); repository = null }

    private data class Preview(val target: Map<String, String>, val consent: SkillActivationConsent, val details: String)

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        var entries by remember { mutableStateOf<List<LocalSkillCatalogEntry>>(emptyList()) }
        var query by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf(SkillImportKind.LOCAL_DIRECTORY) }
        var location by remember { mutableStateOf("") }
        var version by remember { mutableStateOf("1.0.0") }
        var skillId by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var revision by remember { mutableStateOf("") }
        var origins by remember { mutableStateOf("") }
        var network by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<LocalSkillCatalogEntry?>(null) }
        var evidence by remember { mutableStateOf("") }
        var originReviewed by remember { mutableStateOf(false) }
        var licenseReviewed by remember { mutableStateOf(false) }
        var contentReviewed by remember { mutableStateOf(false) }
        var details by remember { mutableStateOf("") }
        var preview by remember { mutableStateOf<Preview?>(null) }
        var approvedChanges by remember { mutableStateOf(false) }
        var approvedPermissions by remember { mutableStateOf(false) }
        var backupPath by remember { mutableStateOf("") }
        var backupHash by remember { mutableStateOf("") }
        var recoveryConfirmed by remember { mutableStateOf(false) }
        var readyText by remember { mutableStateOf("") }
        var textSkillId by remember { mutableStateOf("") }
        var textVersion by remember { mutableStateOf("1.0.0") }
        var textName by remember { mutableStateOf("") }
        var textDescription by remember { mutableStateOf("") }
        var preparedText by remember { mutableStateOf<ValidatedSkillImport?>(null) }
        var preparedTextDetails by remember { mutableStateOf("") }
        var textInstallConfirmed by remember { mutableStateOf(false) }

        fun action(block: suspend () -> String) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    notice = withContext(Dispatchers.IO) { block() }
                    entries = withContext(Dispatchers.IO) { repo().catalog() }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { notice = e.message ?: "Операция не завершена" }
                finally { busy = false }
            }
        }
        suspend fun targetWithDependencies(key: String): Map<String, String> {
            val state = repo().snapshot()
            val required = mutableMapOf<String, String>()
            fun include(k: String) {
                val m = state.installed.getValue(k).pkg.manifest
                val previous = required.putIfAbsent(m.id, k)
                require(previous == null || previous == k) { "Конфликт версий зависимости ${m.id}" }
                if (previous != null) return
                m.dependencies.forEach { include("${it.id}@${it.version}") }
            }
            include(key)
            val target = state.active + required
            SkillPackageFormat.validateGraph(target.values.map { state.installed.getValue(it).pkg.manifest }, host)
            return target
        }

        suspend fun makePreview(target: Map<String, String>): Preview {
            val s = repo().snapshot()
            val releases = target.values.map { s.installed.getValue(it).pkg }
            val permissions = releases.flatMap { p ->
                p.manifest.permissions - s.active[p.manifest.id]?.let { s.installed.getValue(it).pkg.manifest.permissions }.orEmpty()
            }.toSet()
            val text = buildString {
                appendLine("Активный набор: ${s.active}\nПосле подтверждения: $target")
                appendLine("Новые разрешения: ${permissions.ifEmpty { emptySet() }}")
                for (p in releases) {
                    if (s.active[p.manifest.id] == p.key) continue
                    val diff = repo().diff(p.key)
                    appendLine("\n${p.key}\nДо: ${diff.before?.let { describe(it) } ?: "не установлен"}")
                    appendLine("После: ${describe(diff.after)}")
                    appendLine("Источник до: ${diff.oldSource}; после: ${diff.newSource}")
                    appendLine("Добавлены: ${diff.addedFiles}; удалены: ${diff.removedFiles}; изменены: ${diff.changedFiles}")
                    diff.resources.forEach { appendLine(resourceDetails(it)) }
                    appendLine("Инструкция до:\n${diff.oldInstructions.orEmpty()}\nИнструкция после:\n${diff.newInstructions}")
                }
            }
            return Preview(target, SkillActivationConsent(s.generation, releases.associate { it.key to it.checksum }, true, permissions), text)
        }

        LaunchedEffect(Unit) { action { "Хранилище открыто. Импортированные версии остаются в карантине до проверки." } }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperText(title, style = LocalPaperTypography.current.headline)
            PaperText("Активные версии подбираются по задаче в чате. Только текстовый API-профиль, до 6 сообщений и 24 000 символов, без вложений. Скрипты и доступ к файлам/сети отключены: изоляция и ограничения ресурсов не подтверждены. В coding пакеты не передаются.")
            PaperText("Пакеты хранятся на этом устройстве. Импорт не запускает скрипты; активация требует отдельной проверки и подтверждения.")
            if (notice.isNotBlank()) PaperText(notice)
            if (busy) PaperProgress(Modifier.fillMaxWidth())
            PaperInput(query, { query = it }, label = { PaperText("Поиск по имени, источнику, лицензии и версии") }, modifier = Modifier.fillMaxWidth())
            entries.filter { entry ->
                val m = entry.release.pkg.manifest
                val searchable = "${m.id} ${m.name} ${m.description} ${m.version} ${m.license} ${entry.source.location}".lowercase()
                query.lowercase().split(Regex("\\s+")).all { it in searchable }
            }.forEach { entry ->
                val m = entry.release.pkg.manifest
                PaperPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        PaperText("${m.name} · ${m.version} · ${if (entry.active) "активен" else if (entry.release.status == SkillCandidateStatus.QUARANTINED) "карантин" else "проверен"}")
                        PaperText("Источник: ${entry.source.location}${entry.source.revision?.let { " @ $it" }.orEmpty()}")
                        PaperText("Заявлено автором: ${m.origin.location ?: "происхождение неизвестно"}; лицензия: ${m.license ?: "неизвестна"}")
                        PaperText("Разрешения: ${m.permissions.joinToString().ifEmpty { "не заявлены" }}")
                        if (entry.active) PaperText("Выбор в чате: @skill:${m.id} текст задачи")
                        PaperAction(enabled = !busy, onClick = {
                            selected = entry; evidence = ""; originReviewed = false; licenseReviewed = false; contentReviewed = false
                            preview = null; approvedChanges = false; approvedPermissions = false
                            action {
                                val diff = repo().diff(entry.release.pkg.key)
                                details = describe(diff.after) + "\n" + diff.newInstructions + "\n" + diff.resources.joinToString("\n") { resourceDetails(it) }
                                "Открыта версия ${entry.release.pkg.key}"
                            }
                        }) { PaperText("Просмотр и проверка") }
                        PaperAction(enabled = !busy && entry.release.status == SkillCandidateStatus.VERIFIED, onClick = {
                            action {
                                preview = makePreview(targetWithDependencies(entry.release.pkg.key))
                                approvedChanges = false; approvedPermissions = false
                                "Проверьте изменения перед активацией"
                            }
                        }) { PaperText("Сравнить и активировать") }
                        if (entry.active) PaperAction(enabled = !busy, onClick = {
                            action { preview = makePreview(repo().snapshot().active - m.id); approvedChanges = false; approvedPermissions = false; "Проверьте отключение навыка" }
                        }) { PaperText("Отключить…") }
                    }
                }
            }
            selected?.let { entry ->
                PaperText("Проверка ${entry.release.pkg.key}", style = LocalPaperTypography.current.title)
                PaperText("SHA-256: ${entry.release.pkg.checksum}")
                PaperText(details)
                PaperInput(evidence, { evidence = it }, label = { PaperText("Кем создан пакет, основание лицензии и результат проверки файлов") }, modifier = Modifier.fillMaxWidth())
                Check("Происхождение установлено", originReviewed) { originReviewed = it }
                Check("Лицензия установлена", licenseReviewed) { licenseReviewed = it }
                Check("Инструкции и ресурсы проверены", contentReviewed) { contentReviewed = it }
                PaperAction(enabled = !busy && evidence.isNotBlank(), onClick = {
                    action {
                        repo().review(entry.release.pkg.key, SkillPackageReview(entry.release.pkg.checksum, "local-user", evidence, originReviewed, licenseReviewed, contentReviewed))
                        selected = null; "Результат проверки сохранён"
                    }
                }) { PaperText("Сохранить проверку") }
            }
            preview?.let { p ->
                PaperText("Подтверждение изменения", style = LocalPaperTypography.current.title)
                PaperText(p.details)
                Check("Изменения просмотрены", approvedChanges) { approvedChanges = it }
                if (p.consent.permissions.isNotEmpty()) {
                    Check("Отдельно разрешаю новые возможности: ${p.consent.permissions.sortedBy { it.name }.joinToString()}", approvedPermissions) { approvedPermissions = it }
                }
                PaperAction(enabled = !busy && approvedChanges && (p.consent.permissions.isEmpty() || approvedPermissions), onClick = {
                    action {
                        repo().activate(p.target, activationApproval(p.consent, approvedChanges, approvedPermissions))
                        preview = null; "Активный набор сохранён"
                    }
                }) { PaperText("Подтвердить активацию") }
            }
            PaperAction(enabled = !busy, onClick = {
                action { preview = makePreview(repo().snapshot().previousActive ?: error("Нет версии для отката")); approvedChanges = false; approvedPermissions = false; "Проверьте откат всего активного набора" }
            }) { PaperText("Сравнить с предыдущим набором…") }
            PaperDivider()
            PaperText("Импорт", style = LocalPaperTypography.current.title)
            Row { listOf(SkillImportKind.LOCAL_DIRECTORY, SkillImportKind.ZIP, SkillImportKind.GIT, SkillImportKind.HTTPS_PACKAGE).forEach { option ->
                PaperAction(enabled = !busy, onClick = { kind = option; network = false }) {
                PaperText(when (option) { SkillImportKind.LOCAL_DIRECTORY -> "Каталог"; SkillImportKind.ZIP -> "ZIP"; SkillImportKind.GIT -> "GitHub"; SkillImportKind.HTTPS_PACKAGE -> "HTTPS"; SkillImportKind.READY_TEXT -> error("Текстовый импорт открывается отдельным редактором") } + if (kind == option) " ✓" else "")
                }
            } }
            PaperInput(location, { location = it; network = false }, label = { PaperText(if (kind in setOf(SkillImportKind.GIT, SkillImportKind.HTTPS_PACKAGE)) "URL открытого источника" else "Полный путь к каталогу или ZIP") }, modifier = Modifier.fillMaxWidth())
            if (kind == SkillImportKind.LOCAL_DIRECTORY || kind == SkillImportKind.GIT) {
                PaperText("Для пакета без манифеста задайте метаданные. Автор и лицензия останутся неизвестными. Совместимость: desktop, хост 1.x.")
                PaperInput(skillId, { skillId = it }, label = { PaperText("ID навыка, например local.summary") })
                PaperInput(version, { version = it }, label = { PaperText("Фиксированная версия") })
                PaperInput(name, { name = it }, label = { PaperText("Название") })
                PaperInput(description, { description = it }, label = { PaperText("Когда применять") })
            }
            if (kind == SkillImportKind.GIT || kind == SkillImportKind.HTTPS_PACKAGE) {
                PaperInput(revision, { revision = it; network = false }, label = { PaperText(if (kind == SkillImportKind.GIT) "Полный commit SHA (40 символов)" else "SHA-256 манифеста из источника") }, modifier = Modifier.fillMaxWidth())
                PaperInput(origins, { origins = it; network = false }, label = { PaperText("Одобренные HTTPS-источники через запятую") }, modifier = Modifier.fillMaxWidth())
                PaperText("Для GitHub укажите https://github.com и https://codeload.github.com. Каждый адрес перенаправления также должен быть одобрен.")
                Check("Разрешаю загрузку с этих источников. Отправляется только запрос пакета", network) { network = it }
            }
            PaperAction(enabled = !busy && location.isNotBlank(), onClick = {
                action {
                    val importer = SkillPackageImporter(repo(), host, origins.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                    val metadata = if (skillId.isNotBlank()) SkillLocalMetadata(skillId, version, name, description, SkillCompatibility("1.0.0", "2.0.0", setOf("desktop"))) else null
                    when (kind) {
                        SkillImportKind.LOCAL_DIRECTORY -> importer.directory(Path.of(location), metadata)
                        SkillImportKind.ZIP -> importer.zip(Path.of(location))
                        SkillImportKind.GIT -> importer.git(location, revision, network, metadata)
                        SkillImportKind.HTTPS_PACKAGE -> importer.https(location, revision, network)
                        SkillImportKind.READY_TEXT -> error("Текстовый импорт ещё не подключён к этому экрану")
                    }
                    network = false; "Пакет импортирован в карантин"
                }
            }) { PaperText("Импортировать в карантин") }
            PaperDivider()
            PaperText("Готовый SKILL.md", style = LocalPaperTypography.current.title)
            PaperText("Текст и YAML frontmatter проверяются до записи. Метаданные ниже принадлежат этому устройству и не берутся из текста.")
            PaperInput(readyText, {
                readyText = it; preparedText = null; preparedTextDetails = ""; textInstallConfirmed = false
            }, label = { PaperText("Полный текст SKILL.md") }, minLines = 8, modifier = Modifier.fillMaxWidth())
            PaperInput(textSkillId, {
                textSkillId = it; preparedText = null; preparedTextDetails = ""; textInstallConfirmed = false
            }, label = { PaperText("Локальный ID навыка") }, modifier = Modifier.fillMaxWidth())
            PaperInput(textVersion, {
                textVersion = it; preparedText = null; preparedTextDetails = ""; textInstallConfirmed = false
            }, label = { PaperText("Фиксированная версия") }, modifier = Modifier.fillMaxWidth())
            PaperInput(textName, {
                textName = it; preparedText = null; preparedTextDetails = ""; textInstallConfirmed = false
            }, label = { PaperText("Локальное название") }, modifier = Modifier.fillMaxWidth())
            PaperInput(textDescription, {
                textDescription = it; preparedText = null; preparedTextDetails = ""; textInstallConfirmed = false
            }, label = { PaperText("Когда применять") }, modifier = Modifier.fillMaxWidth())
            PaperAction(enabled = !busy && readyText.isNotBlank(), onClick = {
                action {
                    val metadata = SkillLocalMetadata(textSkillId, textVersion, textName, textDescription,
                        SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")))
                    val prepared = SkillPackageImporter(repo(), host).prepareSkillMarkdown(readyText, metadata)
                    preparedText = prepared
                    val frontmatter = SkillPackageImporter.parseSkillMarkdownFrontmatter(readyText)?.fields.orEmpty()
                    preparedTextDetails = buildString {
                        appendLine("Пакет: ${prepared.pkg.key}")
                        appendLine("Checksum манифеста: ${prepared.pkg.checksum}")
                        appendLine("Источник: ${prepared.source.kind} · ${prepared.source.location}")
                        appendLine("Файлы: ${prepared.entries.joinToString { "${it.path} (${it.bytes.size} B)" }}")
                        append("YAML frontmatter: ${if (frontmatter.isEmpty()) "отсутствует" else frontmatter.entries.joinToString { "${it.key}: ${it.value}" }}")
                    }
                    "Предпросмотр готов. Импорт ещё не выполнен."
                }
            }) { PaperText("Проверить и показать предпросмотр") }
            preparedText?.let { prepared ->
                PaperText("Предпросмотр готового текста", style = LocalPaperTypography.current.label)
                PaperText(preparedTextDetails)
                Check("Подтверждаю импорт именно этого checksum в карантин", textInstallConfirmed) { textInstallConfirmed = it }
                PaperAction(enabled = !busy && textInstallConfirmed, onClick = {
                    action {
                        repo().install(prepared)
                        preparedText = null; preparedTextDetails = ""; textInstallConfirmed = false
                        "Готовый текст сохранён в карантин. Для применения нужны отдельные review и подключение."
                    }
                }) { PaperText("Сохранить в карантин") }
                PaperAction(enabled = !busy, onClick = {
                    preparedText = null; preparedTextDetails = ""; textInstallConfirmed = false; notice = "Предпросмотр отменён; пакет не сохранён"
                }) { PaperText("Отменить предпросмотр") }
            }
            PaperDivider()
            PaperText("Резервная копия", style = LocalPaperTypography.current.title)
            PaperInput(backupPath, { backupPath = it; recoveryConfirmed = false }, label = { PaperText("Полный путь к файлу резервной копии") }, modifier = Modifier.fillMaxWidth())
            PaperAction(enabled = !busy && backupPath.isNotBlank(), onClick = {
                action { backupHash = repo().backup(Path.of(backupPath)); "Копия сохранена. Сохраните её отпечаток отдельно для восстановления." }
            }) { PaperText("Создать копию") }
            PaperInput(backupHash, { backupHash = it; recoveryConfirmed = false }, label = { PaperText("Сохранённый SHA-256 резервной копии") }, modifier = Modifier.fillMaxWidth())
            Check("Доверяю этой локальной копии и подтверждаю замену библиотеки и проверок", recoveryConfirmed) { recoveryConfirmed = it }
            PaperAction(enabled = !busy && recoveryConfirmed && backupHash.isNotBlank() && backupPath.isNotBlank(), onClick = {
                action { repo().restore(Path.of(backupPath), backupHash, recoveryConfirmed); preview = null; selected = null; recoveryConfirmed = false; "Библиотека восстановлена и проверена" }
            }) { PaperText("Восстановить") }
        }
    }

    companion object {
        internal fun activationApproval(preview: SkillActivationConsent, changesReviewed: Boolean, permissionsApproved: Boolean) =
            preview.copy(reviewedChanges = changesReviewed, permissions = if (permissionsApproved) preview.permissions else emptySet())
    }

    private fun resourceDetails(r: SkillResourceDiff): String =
        "Ресурс ${r.path}\nДо: ${r.beforeText ?: if (r.before == null) "отсутствует" else "двоичный или более 64 KiB; проверьте исходный файл"}\n" +
            "После: ${r.afterText ?: if (r.after == null) "отсутствует" else "двоичный или более 64 KiB; проверьте исходный файл"}"

    private fun describe(m: SkillPackageManifest): String = buildString {
        appendLine("${m.name} (${m.id}), версия ${m.version}: ${m.description}")
        appendLine("Происхождение: ${m.origin}; лицензия: ${m.license ?: "неизвестна"}")
        appendLine("Разрешения: ${m.permissions}; зависимости: ${m.dependencies}")
        appendLine("Совместимость: хост ${m.compatibility.minHost} — ${m.compatibility.maxHostExclusive} (не включая), ${m.compatibility.platforms}")
        m.files.forEach { appendLine("${it.path}: ${it.size} байт, SHA-256 ${it.sha256}") }
    }

    @Composable
    private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(checked, onChange); PaperText(label) }
    }
}
