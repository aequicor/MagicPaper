package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.designsystem.*

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.skills.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.plugins.PersistentPlugin
import io.aequicor.magicpaper.data.storage.DraftRepository
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Package management is separate from legacy skill drafts: importing never enables instructions. */
class LocalSkillsPlugin(private val root: Path, draftRepository: DraftRepository, applicationScope: CoroutineScope) : MagicPlugin, AutoCloseable, PersistentPlugin {
    internal val forms = SkillsFormDrafts(draftRepository, applicationScope)
    override suspend fun flushDrafts() = forms.flush()
    override suspend fun prepareForReset() = forms.prepareForReset()
    override fun resumeAfterReset() = forms.resumeAfterReset()
    override suspend fun removeProjectDrafts(projectId: String, planIds: Set<String>?) { if (planIds == null) forms.removeProject(projectId) }
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
        val operation = forms.action("library", "LocalSkillsPlugin")
        val operationState by operation.state.collectAsState()
        val busy = operationState.busy || operationState.cleanupPending
        var notice by operation.notice(operationState)
        var entries by remember { mutableStateOf<List<LocalSkillCatalogEntry>>(emptyList()) }
        val libraryOwner = forms.library()
        val libraryState by libraryOwner.draft.state.collectAsState()
        val importOwner = forms.imports()
        val importState by importOwner.draft.state.collectAsState()
        val textOwner = forms.text()
        val textState by textOwner.draft.state.collectAsState()
        val backupOwner = forms.backup()
        val backupState by backupOwner.draft.state.collectAsState()
        val activationOwner = forms.activation()
        val activationState by activationOwner.draft.state.collectAsState()
        var snapshot by remember { mutableStateOf(SkillReleaseSnapshot()) }
        var details by remember { mutableStateOf("") }
        var selected: LocalSkillCatalogEntry? by libraryOwner.field(libraryState,
            { selected -> entries.firstOrNull { it.release.pkg.key == selected.reviewKey && it.release.pkg.checksum == selected.reviewChecksum } },
            { entry -> copy(reviewKey = entry?.release?.pkg?.key, reviewChecksum = entry?.release?.pkg?.checksum) })
        val reviewOwner = forms.review(libraryState.value.reviewKey.orEmpty(), libraryState.value.reviewChecksum.orEmpty())
        val reviewState by reviewOwner.draft.state.collectAsState()
        var query by libraryOwner.field(libraryState, { it.query }, { copy(query = it) })
        var kind by importOwner.field(importState, { it.kind }, { copy(kind = it, network = false) })
        var location by importOwner.field(importState, { it.location }, { copy(location = it, network = false) })
        var version by importOwner.field(importState, { it.version }, { copy(version = it) })
        var skillId by importOwner.field(importState, { it.skillId }, { copy(skillId = it) })
        var name by importOwner.field(importState, { it.name }, { copy(name = it) })
        var description by importOwner.field(importState, { it.description }, { copy(description = it) })
        var revision by importOwner.field(importState, { it.revision }, { copy(revision = it, network = false) })
        var origins by importOwner.field(importState, { it.origins }, { copy(origins = it, network = false) })
        var network by importOwner.field(importState, { it.network }, { copy(network = it) })
        var evidence by reviewOwner.field(reviewState, { it.evidence }, { copy(evidence = it) })
        var originReviewed by reviewOwner.field(reviewState, { it.origin }, { copy(origin = it) })
        var licenseReviewed by reviewOwner.field(reviewState, { it.license }, { copy(license = it) })
        var contentReviewed by reviewOwner.field(reviewState, { it.content }, { copy(content = it) })
        var backupPath by backupOwner.field(backupState, { it.path }, { copy(path = it, confirmed = false) })
        var backupHash by backupOwner.field(backupState, { it.hash }, { copy(hash = it, confirmed = false) })
        var recoveryConfirmed by backupOwner.field(backupState, { it.confirmed }, { copy(confirmed = it) })
        var readyText by textOwner.field(textState, { it.readyText }, { copy(readyText = it, previewChecksum = null, confirmed = false) })
        var textSkillId by textOwner.field(textState, { it.skillId }, { copy(skillId = it, previewChecksum = null, confirmed = false) })
        var textVersion by textOwner.field(textState, { it.version }, { copy(version = it, previewChecksum = null, confirmed = false) })
        var textName by textOwner.field(textState, { it.name }, { copy(name = it, previewChecksum = null, confirmed = false) })
        var textDescription by textOwner.field(textState, { it.description }, { copy(description = it, previewChecksum = null, confirmed = false) })
        var textInstallConfirmed by textOwner.field(textState, { it.confirmed }, { copy(confirmed = it) })
        var approvedChanges by activationOwner.field(activationState, { it.changes }, { copy(changes = it) })
        var approvedPermissions by activationOwner.field(activationState, { it.permissionConsent }, { copy(permissionConsent = it) })
        var preview: Preview? by activationOwner.field(activationState,
            { value -> value.target?.let { Preview(it, SkillActivationConsent(value.generation, value.checksums, false, value.permissions), value.details) } },
            { value -> value?.let { SkillActivationForm(it.target, it.consent.generation, it.consent.targetChecksums, it.consent.permissions, it.details) } ?: SkillActivationForm() })
        var preparedText by remember { mutableStateOf<ValidatedSkillImport?>(null) }
        var preparedTextDetails by remember { mutableStateOf("") }

        fun action(block: suspend () -> String) = operation.launch(block = block)
        LaunchedEffect(busy) {
            if (!busy) {
                try { val fresh = withContext(Dispatchers.IO) { repo().catalog() to repo().snapshot() }; entries = fresh.first; snapshot = fresh.second }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { AppLog.error("LocalSkillsPlugin", "refresh_failed", failure); notice = "Не удалось обновить библиотеку. Откройте панель снова." }
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

        suspend fun makePreview(target: Map<String, String>): Preview = withContext(Dispatchers.IO) {
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
            Preview(target, SkillActivationConsent(s.generation, releases.associate { it.key to it.checksum }, true, permissions), text)
        }

        LaunchedEffect(libraryState.value.reviewKey, libraryState.value.reviewChecksum, entries) {
            selected?.let { entry ->
                try {
                    details = withContext(Dispatchers.IO) { val diff = repo().diff(entry.release.pkg.key); describe(diff.after) + "\n" + diff.newInstructions + "\n" + diff.resources.joinToString("\n") { resourceDetails(it) } }
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { AppLog.error("LocalSkillsPlugin", "review_restore_failed", error); notice = "Не удалось открыть сохранённую проверку." }
            }
        }
        LaunchedEffect(textState.loaded, textState.value.previewChecksum) {
            val value = textState.value
            if (textState.loaded && value.previewChecksum != null && preparedText == null) {
                try {
                    val restored = withContext(Dispatchers.IO) { SkillPackageImporter(repo(), host).prepareSkillMarkdown(value.readyText,
                        SkillLocalMetadata(value.skillId, value.version, value.name, value.description, SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")))) }
                    require(restored.pkg.checksum == value.previewChecksum)
                    preparedText = restored; preparedTextDetails = "Пакет: ${restored.pkg.key}\nSHA-256: ${restored.pkg.checksum}"
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { AppLog.error("LocalSkillsPlugin", "preview_restore_failed", error); notice = "Проверьте текст и откройте новый предпросмотр." }
            }
        }
        LaunchedEffect(Unit) { action { "Хранилище открыто. Импортированные версии остаются в карантине до проверки." } }
        PaperScrollColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperText(title, style = LocalPaperTypography.current.headline)
            PaperText("Активные версии подбираются по задаче в чате. Только текстовый API-профиль, до 6 сообщений и 24 000 символов, без вложений. Скрипты и доступ к файлам/сети отключены: изоляция и ограничения ресурсов не подтверждены. В coding пакеты не передаются.")
            PaperText("Пакеты хранятся на этом устройстве. Импорт не запускает скрипты; активация требует отдельной проверки и подтверждения.")
            if (notice.isNotBlank()) PaperText(notice)
            if (operationState.cleanupPending) PaperAction(onClick = operation::retryCleanup) { PaperText("Повторить очистку") }
            for ((state, retry) in listOf(libraryState to { libraryOwner.draft.retry() }, importState to { importOwner.draft.retry() }, textState to { textOwner.draft.retry() }, backupState to { backupOwner.draft.retry() }, activationState to { activationOwner.draft.retry() }, reviewState to { reviewOwner.draft.retry() })) {
                if (state.error != null) { PaperText("Черновик не сохранён.", color = LocalPaperColors.current.error); PaperAction(onClick = retry) { PaperText("Повторить сохранение") } }
            }
            if (!listOf(libraryState, importState, textState, backupState, activationState, reviewState).all { it.loaded }) { PaperProgress(Modifier.fillMaxWidth()); return@PaperScrollColumn }
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
                            selected = entry
                            preview = null; approvedChanges = false; approvedPermissions = false
                            action {
                                val diff = withContext(Dispatchers.IO) { repo().diff(entry.release.pkg.key) }
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
                    val capturedSession = reviewOwner.draft
                    val captured = capturedSession.state.value
                    val selectionVersion = libraryOwner.draft.state.value.version
                    action {
                        withContext(Dispatchers.IO) { repo().review(entry.release.pkg.key, SkillPackageReview(entry.release.pkg.checksum, "local-user", captured.value.evidence, captured.value.origin, captured.value.license, captured.value.content)) }
                        if (operation.accepted { capturedSession.awaitSaved(); capturedSession.clearIfUnchanged(captured.version) } && libraryOwner.draft.state.value.version == selectionVersion) selected = null
                        "Результат проверки сохранён"
                    }
                }) { PaperText("Сохранить проверку") }
            }
            preview?.let { p ->
                PaperText("Подтверждение изменения", style = LocalPaperTypography.current.title)
                PaperText(p.details)
                if (!activationState.value.matches(snapshot)) PaperText("Состав библиотеки изменился. Откройте новое сравнение.", color = LocalPaperColors.current.error)
                Check("Изменения просмотрены", approvedChanges) { approvedChanges = it }
                if (p.consent.permissions.isNotEmpty()) {
                    Check("Отдельно разрешаю новые возможности: ${p.consent.permissions.sortedBy { it.name }.joinToString()}", approvedPermissions) { approvedPermissions = it }
                }
                PaperAction(enabled = !busy && activationState.value.matches(snapshot) && approvedChanges && (p.consent.permissions.isEmpty() || approvedPermissions), onClick = {
                    val capturedSession = activationOwner.draft
                    val captured = capturedSession.state.value
                    action {
                        withContext(Dispatchers.IO) { repo().activate(p.target, captured.value.consent()) }
                        operation.accepted { capturedSession.awaitSaved(); capturedSession.clearIfUnchanged(captured.version) }
                        "Активный набор сохранён"
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
                val capturedSession = importOwner.draft
                    val captured = capturedSession.state.value
                action {
                    val input = captured.value
                    withContext(Dispatchers.IO) {
                    val importer = SkillPackageImporter(repo(), host, input.origins.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                    val metadata = if (input.skillId.isNotBlank()) SkillLocalMetadata(input.skillId, input.version, input.name, input.description, SkillCompatibility("1.0.0", "2.0.0", setOf("desktop"))) else null
                    when (input.kind) {
                        SkillImportKind.LOCAL_DIRECTORY -> importer.directory(Path.of(input.location), metadata)
                        SkillImportKind.ZIP -> importer.zip(Path.of(input.location))
                        SkillImportKind.GIT -> importer.git(input.location, input.revision, input.network, metadata)
                        SkillImportKind.HTTPS_PACKAGE -> importer.https(input.location, input.revision, input.network)
                        SkillImportKind.READY_TEXT -> error("Текстовый импорт ещё не подключён к этому экрану")
                    }
                    }
                    operation.accepted { capturedSession.awaitSaved(); capturedSession.clearIfUnchanged(captured.version) }
                    "Пакет импортирован в карантин"
                }
            }) { PaperText("Импортировать в карантин") }
            PaperDivider()
            PaperText("Готовый SKILL.md", style = LocalPaperTypography.current.title)
            PaperText("Текст и YAML frontmatter проверяются до записи. Метаданные ниже принадлежат этому устройству и не берутся из текста.")
            PaperInput(readyText, {
                readyText = it; preparedText = null; preparedTextDetails = ""; textOwner.update { it.copy(previewChecksum = null, confirmed = false) }
            }, label = { PaperText("Полный текст SKILL.md") }, minLines = 8, modifier = Modifier.fillMaxWidth())
            PaperInput(textSkillId, {
                textSkillId = it; preparedText = null; preparedTextDetails = ""; textOwner.update { it.copy(previewChecksum = null, confirmed = false) }
            }, label = { PaperText("Локальный ID навыка") }, modifier = Modifier.fillMaxWidth())
            PaperInput(textVersion, {
                textVersion = it; preparedText = null; preparedTextDetails = ""; textOwner.update { it.copy(previewChecksum = null, confirmed = false) }
            }, label = { PaperText("Фиксированная версия") }, modifier = Modifier.fillMaxWidth())
            PaperInput(textName, {
                textName = it; preparedText = null; preparedTextDetails = ""; textOwner.update { it.copy(previewChecksum = null, confirmed = false) }
            }, label = { PaperText("Локальное название") }, modifier = Modifier.fillMaxWidth())
            PaperInput(textDescription, {
                textDescription = it; preparedText = null; preparedTextDetails = ""; textOwner.update { it.copy(previewChecksum = null, confirmed = false) }
            }, label = { PaperText("Когда применять") }, modifier = Modifier.fillMaxWidth())
            PaperAction(enabled = !busy && readyText.isNotBlank(), onClick = {
                val capturedSession = textOwner.draft
                    val captured = capturedSession.state.value
                action {
                    val input = captured.value
                    val metadata = SkillLocalMetadata(input.skillId, input.version, input.name, input.description,
                        SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")))
                    val prepared = withContext(Dispatchers.IO) { SkillPackageImporter(repo(), host).prepareSkillMarkdown(input.readyText, metadata) }
                    if (textOwner.draft.state.value.version != captured.version) return@action "Текст изменён. Откройте новый предпросмотр."
                    preparedText = prepared
                    textOwner.update { it.copy(previewChecksum = prepared.pkg.checksum, confirmed = false) }
                    val frontmatter = SkillPackageImporter.parseSkillMarkdownFrontmatter(input.readyText)?.fields.orEmpty()
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
                    val capturedSession = textOwner.draft
                    val captured = capturedSession.state.value
                    action {
                        require(captured.value.previewChecksum == prepared.pkg.checksum)
                        withContext(Dispatchers.IO) { repo().install(prepared) }
                        if (operation.accepted { capturedSession.awaitSaved(); capturedSession.clearIfUnchanged(captured.version) }) { preparedText = null; preparedTextDetails = "" }
                        "Готовый текст сохранён в карантин. Для применения нужны отдельные review и подключение."
                    }
                }) { PaperText("Сохранить в карантин") }
                PaperAction(enabled = !busy, onClick = {
                    preparedText = null; preparedTextDetails = ""; textOwner.update { it.copy(previewChecksum = null, confirmed = false) }; notice = "Предпросмотр отменён; пакет не сохранён"
                }) { PaperText("Отменить предпросмотр") }
            }
            PaperDivider()
            PaperText("Резервная копия", style = LocalPaperTypography.current.title)
            PaperInput(backupPath, { backupPath = it; recoveryConfirmed = false }, label = { PaperText("Полный путь к файлу резервной копии") }, modifier = Modifier.fillMaxWidth())
            PaperAction(enabled = !busy && backupPath.isNotBlank(), onClick = {
                val capturedSession = backupOwner.draft
                    val captured = capturedSession.state.value
                action {
                    val hash = withContext(Dispatchers.IO) { repo().backup(Path.of(captured.value.path)) }
                    if (backupOwner.draft.state.value.version == captured.version) backupOwner.update { it.copy(hash = hash, confirmed = false) }
                    "Копия сохранена. Сохраните её отпечаток отдельно для восстановления."
                }
            }) { PaperText("Создать копию") }
            PaperInput(backupHash, { backupHash = it; recoveryConfirmed = false }, label = { PaperText("Сохранённый SHA-256 резервной копии") }, modifier = Modifier.fillMaxWidth())
            Check("Доверяю этой локальной копии и подтверждаю замену библиотеки и проверок", recoveryConfirmed) { recoveryConfirmed = it }
            PaperAction(enabled = !busy && recoveryConfirmed && backupHash.isNotBlank() && backupPath.isNotBlank(), onClick = {
                val capturedSession = backupOwner.draft
                    val captured = capturedSession.state.value
                action {
                    withContext(Dispatchers.IO) { repo().restore(Path.of(captured.value.path), captured.value.hash, captured.value.confirmed) }
                    operation.accepted { capturedSession.awaitSaved(); capturedSession.clearIfUnchanged(captured.version) }
                    "Библиотека восстановлена и проверена"
                }
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
