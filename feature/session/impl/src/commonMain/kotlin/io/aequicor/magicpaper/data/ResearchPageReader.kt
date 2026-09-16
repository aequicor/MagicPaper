package io.aequicor.magicpaper.data

import io.aequicor.magicpaper.domain.researchUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable

/** Bounded, text-only fallback for platforms without a native browsing agent. No app credentials are attached. */
class ResearchPageReader(private val client: HttpClient) {
    suspend fun read(url: String): String {
        require(researchUrl(url) != null)
        return client.prepareGet(url).execute { response ->
            check(response.status.isSuccess()) { "Research page HTTP ${response.status.value}" }
            val contentType = response.headers["Content-Type"].orEmpty()
            check(contentType.startsWith("text/") || contentType.startsWith("application/xhtml+xml")) { "Research page is not text" }
            val bytes = ByteArray(1_000_001)
            var count = 0
            val channel = response.bodyAsChannel()
            while (count < bytes.size) {
                val read = channel.readAvailable(bytes, count, bytes.size - count)
                if (read == -1) break
                count += read
            }
            check(count < bytes.size) { "Research page exceeds text limit" }
            val raw = bytes.decodeToString(0, count)
            val text = if (contentType.startsWith("text/plain")) raw.take(30_000) else researchPageText(raw)
            check(text.isNotBlank()) { "Research page contains no readable text" }
            text
        }
    }
}

internal fun researchPageText(html: String): String = html
    .replace(Regex("<(script|style|noscript)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("<[^>]+>"), " ")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("\\s+"), " ").trim().take(30_000)
