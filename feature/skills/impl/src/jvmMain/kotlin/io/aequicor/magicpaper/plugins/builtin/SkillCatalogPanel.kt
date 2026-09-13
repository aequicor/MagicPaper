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
import kotlinx.coroutines.*
import io.aequicor.magicpaper.logging.AppLog

@Composable
internal fun SkillCatalogPanel(
    repository: () -> LocalSkillRepository,
    forms: SkillsFormDrafts,
    projectId: String,
    catalogFactory: () -> GithubSkillCatalog = { GithubSkillCatalog() },
    onConnect: ((String) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val catalog = remember { catalogFactory() }
    val operation = forms.action("catalog:$projectId", "SkillCatalogPanel", projectId)
    val operationState by operation.state.collectAsState()
    val busy = operationState.busy || operationState.cleanupPending
    var notice by operation.notice(operationState)
    val owner = forms.catalog(projectId)
    val state by owner.draft.state.collectAsState()
    var query by owner.field(state, { it.query }, { copy(query = it) })
    var link by owner.field(state, { it.link }, { copy(link = it, network = false) })
    var network by owner.field(state, { it.network }, { copy(network = it) })
    var discovery by remember { mutableStateOf<GithubSkillCatalog.Discovery?>(null) }
    var preview by remember { mutableStateOf<GithubSkillCatalog.Preview?>(null) }
    var local by remember { mutableStateOf<List<LocalSkillCatalogEntry>>(emptyList()) }
    var review: LocalSkillCatalogEntry? by owner.field(state,
        { value -> local.firstOrNull { it.release.pkg.key == value.reviewKey && it.release.pkg.checksum == value.reviewChecksum } },
        { entry -> copy(reviewKey = entry?.release?.pkg?.key, reviewChecksum = entry?.release?.pkg?.checksum) })
    val reviewOwner = forms.review(state.value.reviewKey.orEmpty(), state.value.reviewChecksum.orEmpty())
    val reviewState by reviewOwner.draft.state.collectAsState()
    var details by remember { mutableStateOf("") }
    var evidence by reviewOwner.field(reviewState, { it.evidence }, { copy(evidence = it) })
    var origin by reviewOwner.field(reviewState, { it.origin }, { copy(origin = it) })
    var license by reviewOwner.field(reviewState, { it.license }, { copy(license = it) })
    var content by reviewOwner.field(reviewState, { it.content }, { copy(content = it) })
    fun action(cancellable: Boolean = false, block: suspend () -> Unit) = operation.launch(cancellable) { block(); notice }
    LaunchedEffect(busy) {
        if (!busy) try { local = withContext(Dispatchers.IO) { repository().catalog() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { operation.report(failure) }
    }
    LaunchedEffect(Unit) { action { } }
    LaunchedEffect(state.value.reviewKey, state.value.reviewChecksum, local) {
        review?.let { entry ->
            try { details = withContext(Dispatchers.IO) { val diff = repository().diff(entry.release.pkg.key); "${diff.after}\n${diff.newInstructions}\n" + diff.resources.joinToString("\n") } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { AppLog.error("SkillCatalogPanel", "review_restore_failed", error); notice = "Не удалось открыть сохранённую проверку." }
        }
    }
    Column(Modifier.widthIn(max = 680.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperText("Добавить скилы из репозиториев", style = LocalPaperTypography.current.headline)
        PaperAction(onClick = { onClose() }) { PaperText("Назад к проекту") }
        if (notice.isNotEmpty()) PaperText(notice)
        if (operationState.cleanupPending) PaperAction(onClick = operation::retryCleanup) { PaperText("Повторить очистку") }
        if (state.error != null || reviewState.error != null) { PaperText("Черновик не сохранён.", color = LocalPaperColors.current.error); PaperAction(onClick = { owner.draft.retry(); reviewOwner.draft.retry() }) { PaperText("Повторить сохранение") } }
        if (!state.loaded || !reviewState.loaded) { PaperProgress(Modifier.fillMaxWidth()); return@Column }
        if (busy) {
            PaperProgress(Modifier.fillMaxWidth())
            PaperAction(enabled = operationState.cancellable, onClick = { operation.cancel() }) { PaperText("Отменить загрузку") }
        }
        PaperInput(query, { query = it }, label = { PaperText("Поиск навыков") }, modifier = Modifier.fillMaxWidth())
        PaperText("Проверенные публичные источники")
        val sources = catalog.search(query)
        if (sources.isEmpty()) PaperText("Источники не найдены. Введите ссылку ниже.")
        sources.forEach { source ->
            PaperText(source.name + "\n" + source.description + "\n" + source.url)
            PaperAction(enabled = !busy, onClick = { link = source.url; network = false; discovery = null; preview = null }) { PaperText("Выбрать источник") }
        }
        PaperInput(link, { link = it; network = false; discovery = null; preview = null }, enabled = !busy,
            label = { PaperText("Ссылка на GitHub") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(network, { network = it }, enabled = !busy); PaperText("Разрешаю анонимные GET к github.com, api.github.com, codeload.github.com. Передаётся только адрес источника, без рабочих данных и ключей.") }
        PaperAction(enabled = !busy && network && link.isNotBlank(), onClick = {
            action(cancellable = true) { discovery = null; preview = null; discovery = catalog.discover(link, network); notice = "Выберите пакет." }
        }) { PaperText("Найти пакеты по ссылке") }
        discovery?.let { d ->
            PaperText("Источник: ${d.repository}\nCommit: ${d.commit}")
            d.packages.forEach { path -> PaperAction(enabled = !busy, onClick = {
                action(cancellable = true) {
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
            }) { PaperText(path) } }
        }
        preview?.let { p ->
            PaperText("Предпросмотр / сравнение")
            PaperText(details)
            PaperText("После (метаданные адаптера, не версия издателя): ${p.manifest}\nSHA-256: ${p.checksum}\nФактический источник: ${p.source}")
            PaperText("Лицензия: ${p.manifest.license ?: "не установлена — подключение блокируется до обоснованного review"}")
            PaperText("Исходный SKILL.md, включая метаданные автора:\n${p.original}")
            PaperText("Лицензионный файл репозитория (применимость не подтверждена):\n${p.licenseEvidence ?: "не найден"}")
            PaperAction(enabled = !busy, onClick = {
                action { withContext(Dispatchers.IO) { catalog.install(repository(), p) }; preview = null; notice = "Пакет сохранён. Требуется проверка." }
            }) { PaperText("Импортировать без подключения") }
            PaperAction(enabled = !busy, onClick = { preview = null }) { PaperText("Отменить предпросмотр") }
        }
        PaperDivider()
        PaperText("Установленные — доступны офлайн")
        val matches = local.filter { (it.release.pkg.manifest.toString() + it.source).contains(query.trim(), true) }
        if (matches.isEmpty()) PaperText("Пакеты не найдены")
        matches.forEach { entry ->
            val p = entry.release.pkg
            PaperText("${p.manifest.name} · ${p.key}\n${entry.source}\nChecksum: ${p.checksum}\nЛицензия: ${p.manifest.license ?: "не установлена"}; ${entry.release.status}")
            if (onConnect != null) {
                val eligible = entry.release.status == SkillCandidateStatus.VERIFIED && entry.release.improvement?.passed != false
                PaperButton("Подключить скилл", enabled = !busy && eligible, onClick = { onConnect(p.key) })
                if (!eligible) PaperText("Для подключения завершите проверку скилла через «Просмотр и проверка».")
            }
            PaperAction(enabled = !busy, onClick = {
                action {
                    val diff = withContext(Dispatchers.IO) { repository().diff(p.key) }
                    preview = null; review = entry
                    details = "${diff.after}\n${diff.newInstructions}\n" + diff.resources.joinToString("\n") { it.toString() }
                }
            }) { PaperText("Просмотр и проверка") }
            if (entry.source.kind == SkillImportKind.GIT) PaperAction(enabled = !busy, onClick = {
                val url = entry.source.location.substringBefore("/tree/")
                link = url; network = false; discovery = null; preview = null
                notice = "Разрешите загрузку с GitHub для проверки обновления."
            }) { PaperText("Проверить обновление…") }
        }
        review?.let { entry ->
            PaperText("Review точного SHA-256: ${entry.release.pkg.checksum}\n$details")
            PaperInput(evidence, { evidence = it }, label = { PaperText("Обоснование происхождения, лицензии и проверки ресурсов") })
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(origin, { origin = it }); PaperText("Происхождение установлено") }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(license, { license = it }); PaperText("Лицензия и её применимость установлены") }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { PaperCheck(content, { content = it }); PaperText("Все инструкции и ресурсы проверены") }
            PaperAction(enabled = !busy && evidence.isNotBlank(), onClick = { action {
                val capturedSession = reviewOwner.draft
                val captured = capturedSession.state.value
                withContext(Dispatchers.IO) { repository().review(entry.release.pkg.key, SkillPackageReview(entry.release.pkg.checksum, "local-user", captured.value.evidence, captured.value.origin, captured.value.license, captured.value.content)) }
                if (operation.accepted { capturedSession.awaitSaved(); capturedSession.clearIfUnchanged(captured.version) } && owner.draft.state.value.value.reviewKey == entry.release.pkg.key) review = null
                notice = "Проверка сохранена."
            } }) { PaperText("Сохранить проверку") }
            PaperAction(onClick = { val capturedSession = reviewOwner.draft
                val captured = capturedSession.state.value.version; action { if (reviewOwner.draft.clearIfUnchanged(captured)) review = null } }) { PaperText("Отмена") }
        }
    }
}
