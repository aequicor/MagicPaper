package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.researchPageProblem
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal data class ResearchBrowserTarget(val notebookId: String, val questionId: String, val resource: ResearchResource)

/** Volatile, bounded snapshots imported explicitly by the user. Scoped to a notebook;
 * neither cookies nor page text enter persisted chat resources. */
internal class ResearchBrowserSources(private val clock: () -> Long) {
    private data class Entry(val text: String, val expires: Long)
    private val entries = MutableStateFlow<Map<Pair<String, String>, Entry>>(emptyMap())

    fun put(target: ResearchBrowserTarget, text: String) {
        val now = clock()
        entries.update { old -> (old.filterValues { it.expires > now } - (target.notebookId to target.resource.key))
            .entries.toList().takeLast(29).associate { it.toPair() } +
            ((target.notebookId to target.resource.key) to Entry(text.take(30_000), now + 30 * 60_000)) }
    }

    fun apply(notebookId: String, resources: List<ResearchResource>): List<ResearchResource> {
        val now = clock()
        return resources.map { resource -> entries.value[notebookId to resource.key]?.takeIf { it.expires > now }
            ?.let { resource.copy(snippet = "", readableText = it.text) } ?: resource }
    }

    fun clear() { entries.value = emptyMap() }
}

/** Application service ownership keeps a human-assisted browser alive across navigation.
 * Each attempt owns its page; replaced/closed attempts cannot publish into a newer one. */
internal class ResearchBrowserRecovery(
    private val browser: ResearchPageBrowser,
    private val scope: CoroutineScope,
    private val onState: (ResearchBrowserState?) -> Unit,
    private val onRead: (ResearchBrowserTarget, ResearchBrowserContent) -> Boolean,
    private val onNotice: (String) -> Unit = {},
) {
    private class Attempt(val target: ResearchBrowserTarget) {
        var job: Job? = null
        var page: ResearchBrowserPage? = null
    }
    private var active: Attempt? = null

    fun open(target: ResearchBrowserTarget) {
        val old = active
        val attempt = Attempt(target)
        active = attempt
        publish(attempt, ResearchBrowserPhase.OPENING)
        attempt.job = scope.launch {
            old?.let { dispose(it) }
            try {
                attempt.page = browser.open(target.resource.url)
                ensureActive()
                publish(attempt, ResearchBrowserPhase.READY)
                log("opened", attempt)
            } catch (cancelled: CancellationException) { disposePage(attempt); throw cancelled }
            catch (error: Exception) { fail(attempt, error, "Не удалось открыть браузер. Повторите открытие страницы.") }
        }
    }

    fun read() {
        val attempt = active ?: return
        if (attempt.job?.isActive == true) return
        val page = attempt.page ?: return open(attempt.target)
        publish(attempt, ResearchBrowserPhase.READING)
        attempt.job = scope.launch {
            try {
                val content = page.read()
                ensureActive()
                researchPageProblem(content.text, content.text, plain = true)?.let { throw ResearchBrowserUnavailable(it) }
                if (active !== attempt) return@launch
                if (!onRead(attempt.target, content)) throw ResearchBrowserUnavailable("Источник уже удалён. Закройте окно и выберите источник заново.")
                log("read.completed", attempt)
                active = null
                onState(null)
                disposePage(attempt)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if ((error as? ResearchBrowserUnavailable)?.requiresReopen == true) disposePage(attempt)
                fail(attempt, error, "Не удалось прочитать страницу. Проверьте её в браузере и повторите чтение.")
            }
        }
    }

    fun dismiss() {
        val attempt = active ?: return
        active = null
        onState(null)
        scope.launch { dispose(attempt); log("closed", attempt) }
    }

    suspend fun close() {
        val attempt = active
        active = null
        if (attempt != null) dispose(attempt)
    }

    private fun publish(attempt: Attempt, phase: ResearchBrowserPhase, problem: String? = null) {
        if (active !== attempt) return
        onState(ResearchBrowserState(attempt.target.questionId, attempt.target.resource.key,
            attempt.target.resource.title, attempt.target.resource.url, phase, attempt.page != null, problem))
    }

    private fun fail(attempt: Attempt, error: Exception, fallback: String) {
        AppLog.error("research.browser", "operation.failed", fields = fields(attempt) +
            mapOf("failure" to error::class.simpleName.orEmpty(), "recovery" to "user_retry"))
        publish(attempt, ResearchBrowserPhase.FAILED, (error as? ResearchBrowserUnavailable)?.reason ?: fallback)
    }

    private suspend fun dispose(attempt: Attempt) = withContext(NonCancellable) {
        attempt.job?.cancelAndJoin()
        disposePage(attempt)
    }

    private suspend fun disposePage(attempt: Attempt) = withContext(NonCancellable) {
        val page = attempt.page ?: return@withContext
        attempt.page = null
        try { page.close() }
        catch (error: Exception) {
            AppLog.error("research.browser", "close.failed", fields = fields(attempt) +
                ("failure" to error::class.simpleName.orEmpty()))
            onNotice("Не удалось закрыть окно браузера. Закройте его вручную.")
        }
    }

    private fun fields(attempt: Attempt) = mapOf("sessionId" to attempt.target.questionId, "entityId" to attempt.target.resource.id)
    private fun log(event: String, attempt: Attempt) = AppLog.info("research.browser", event, fields(attempt))
}
