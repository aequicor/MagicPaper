package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.ResearchPageUnavailable
import io.aequicor.magicpaper.data.researchPageProblem
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class ResearchSourceCheck(val resource: ResearchResource, val problem: String? = null)

/** The session owner checks sources per run. No persistent success cache: access can change.
 * Shared by chat preparation, gateway and the application's research search tool. */
class ResearchSourceAccess(private val readPage: (suspend (String) -> String)? = null) {
    private val reads = Semaphore(4)

    internal suspend fun check(resources: List<ResearchResource>): List<ResearchSourceCheck> = coroutineScope {
        resources.distinctBy { it.key }.map { resource -> async { check(resource) } }.awaitAll()
    }

    internal suspend fun check(resource: ResearchResource): ResearchSourceCheck {
        if (resource.attachment != null || resource.url.isBlank()) return ResearchSourceCheck(resource)
        if (resource.readableText != null) return ResearchSourceCheck(resource.copy(snippet = ""))
        if (readPage == null) return ResearchSourceCheck(resource, "Чтение сайтов недоступно в этом подключении")
        return reads.withPermit {
            try {
                val text = withTimeoutOrNull(8_000) { readPage.invoke(resource.url) }
                    ?: throw ResearchPageUnavailable("Истекло время ожидания страницы")
                researchPageProblem(text, text, plain = true)?.let { throw ResearchPageUnavailable(it) }
                ResearchSourceCheck(resource.copy(snippet = "", readableText = text.take(30_000)))
            } catch (timeout: TimeoutCancellationException) {
                // A reader can have a shorter, nested deadline. It only makes this
                // page unavailable; a cancelled parent still stops the whole run.
                currentCoroutineContext().ensureActive()
                unavailable(resource, timeout, "Истекло время ожидания страницы")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                unavailable(resource, failure, (failure as? ResearchPageUnavailable)?.reason ?: "Не удалось загрузить страницу")
            }
        }
    }

    private fun unavailable(resource: ResearchResource, failure: Exception, problem: String): ResearchSourceCheck {
        AppLog.error("chat", "source.read.failed", fields = mapOf("entityId" to resource.id,
            "failure" to failure::class.simpleName.orEmpty(), "recovery" to "skip_source"))
        return ResearchSourceCheck(resource.copy(snippet = "", readableText = null), problem)
    }
}

internal fun List<ResearchSourceCheck>.readableSources(): List<ResearchResource> = filter { it.problem == null }.map { it.resource }

internal fun List<ResearchSourceCheck>.unavailableSourceContext(allowSearch: Boolean = true): String = filter { it.problem != null }.takeIf { it.isNotEmpty() }
    ?.joinToString("\n", prefix = "Недоступные источники исключены из доказательной базы. Не используй их сниппеты, аннотации, пересказы из истории и не цитируй эти URL. " +
        if (allowSearch) "Найди доступный первоисточник или скажи, что данных недостаточно.\n"
        else "Не ищи замену в интернете. Сообщи, что данных недостаточно, и предложи загрузить файл или вставить текст.\n") {
        "${it.resource.url}: ${it.problem}"
    }.orEmpty()
