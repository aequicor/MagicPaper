package io.aequicor.magicpaper.data

import io.aequicor.magicpaper.domain.researchUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ResearchPageUnavailable(val reason: String) : IllegalStateException(reason)

/** Bounded, text-only fallback for platforms without a native browsing agent. No app credentials are attached. */
class ResearchPageReader(private val client: HttpClient) {
    suspend fun read(url: String): String = withContext(Dispatchers.Default) {
        require(researchUrl(url) != null)
        client.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) throw ResearchPageUnavailable("Ошибка HTTP ${response.status.value}")
            val contentType = response.headers["Content-Type"].orEmpty().lowercase()
            if (!contentType.startsWith("text/") && !contentType.startsWith("application/xhtml+xml"))
                throw ResearchPageUnavailable("Формат страницы не поддерживается")
            val bytes = ByteArray(1_000_001)
            var count = 0
            val channel = response.bodyAsChannel()
            while (count < bytes.size) {
                val read = channel.readAvailable(bytes, count, bytes.size - count)
                if (read == -1) break
                count += read
            }
            if (count == bytes.size) throw ResearchPageUnavailable("Страница слишком большая для чтения")
            val raw = bytes.decodeToString(0, count)
            val text = if (contentType.startsWith("text/plain")) raw.take(30_000) else researchPageText(raw)
            researchPageProblem(raw, text, contentType.startsWith("text/plain"))?.let { throw ResearchPageUnavailable(it) }
            text
        }
    }
}

/** Match interstitials, not articles that merely discuss CAPTCHA, logins or paywalls. */
internal fun researchPageProblem(raw: String, text: String, plain: Boolean = false): String? {
    if (text.isBlank()) return "На странице нет доступного текста"
    if (!plain && researchPageText(raw.replace(Regex("<head\\b[^>]*>[\\s\\S]*?</head>", RegexOption.IGNORE_CASE), " ")).isBlank())
        return "На странице нет доступного текста"
    val headings = if (plain) listOf(text.take(180).lowercase()) else Regex("<(title|h1)\\b[^>]*>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
        .findAll(raw).take(3).map { researchPageText(it.groupValues[2]).lowercase() }.toList()
    val heading = headings.joinToString(" ")
    val intro = text.take(1600).lowercase()
    val challengeTitle = Regex("^(just a moment|attention required|access denied|verify (you are|that you are) human|checking your browser|security (check|verification)|robot check|captcha|доступ (запрещён|ограничен)|проверка безопасности)[!.…:]*(\\s*[|–—-].*)?$")
    val challengeMarkup = Regex("(id=[\"']challenge-(form|running)|/cdn-cgi/challenge-platform/|id=[\"']px-captcha)", RegexOption.IGNORE_CASE).containsMatchIn(raw)
    val challengeText = listOf("verify you are human", "verifying you are human", "checking your browser before accessing",
        "complete the security check", "подтвердите, что вы не робот", "подтвердите, что вы человек")
        .any { it in intro }
    if (headings.any(challengeTitle::matches) || (text.length < 5000 && (challengeMarkup || challengeText)))
        return "CAPTCHA или защита сайта"
    if (Regex("^(sign in|log in|login|authentication required|вход|авторизация)(\\b|[ :—-])").containsMatchIn(heading) && text.length < 5000)
        return "Для чтения требуется вход"
    if (text.length < 5000 && listOf("subscribe to read", "subscribe to continue reading", "purchase access to",
            "подпишитесь, чтобы читать", "оформите подписку для чтения").any { it in intro })
        return "Текст закрыт подпиской"
    if (Regex("^(404|page not found|not found|страница не найдена|service unavailable)").containsMatchIn(heading) && text.length < 5000)
        return "Страница недоступна"
    if (text.length < 1000 && listOf("enable javascript", "javascript is required", "включите javascript").any { it in intro })
        return "Для чтения требуется JavaScript"
    return null
}

internal fun researchPageText(html: String): String = html
    .replace(Regex("<(script|style|noscript)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("<[^>]+>"), " ")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("\\s+"), " ").trim().take(30_000)
