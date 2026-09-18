package io.aequicor.magicpaper.data.media

import io.aequicor.magicpaper.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

/** Provider wire formats stop here. No chat parameters, retry loop or credential-bearing logging. */
class HttpMediaGenerationGateway(private val client: HttpClient, private val json: Json) : MediaGenerationGateway {
    override suspend fun submit(
        profile: LlmProfile,
        selection: MediaModelSelection,
        request: MediaGenerationRequest,
    ): MediaSubmission {
        validate(profile, selection, request.kind)
        if (request.prompt.isBlank() || request.prompt.length > 32_000 ||
            request.width !in 64..4096 || request.height !in 64..4096 ||
            (request.kind == MediaKind.VIDEO && request.durationSeconds !in 1..30)) {
            throw failure(MediaFailureKind.VALIDATION, "Проверьте описание, размер и длительность.")
        }
        val path = when (selection.protocol) {
            MediaProtocol.OPENAI_IMAGES -> "images/generations"
            MediaProtocol.DASHSCOPE_IMAGE -> when {
                legacyAsyncImage(selection.modelId) -> "services/aigc/text2image/image-synthesis"
                selection.modelId.startsWith("qwen-image-3.0") -> "services/aigc/image-generation/generation"
                else -> "services/aigc/multimodal-generation/generation"
            }
            MediaProtocol.DASHSCOPE_VIDEO -> "services/aigc/video-generation/video-synthesis"
        }
        val url = endpoint(profile, selection, path)
        val body = payload(selection, request)
        return attempt(submitting = true) {
            val response = client.post(url) {
                timeout { requestTimeoutMillis = SUBMIT_TIMEOUT_MS }
                authenticate(profile)
                contentType(ContentType.Application.Json)
                if (isAsync(selection)) header("X-DashScope-Async", "enable")
                setBody(body.toString())
            }
            val root = responseObject(response, submitting = true)
            rejectProviderError(root)
            if (isAsync(selection)) {
                val output = root.obj("output") ?: unknownResponse()
                val id = output.string("task_id")?.takeIf(::validJobId) ?: unknownResponse()
                MediaSubmission.Accepted(id)
            } else when (selection.protocol) {
                MediaProtocol.OPENAI_IMAGES -> MediaSubmission.Completed(openAiOutput(root, request))
                MediaProtocol.DASHSCOPE_IMAGE -> MediaSubmission.Completed(qwenOutput(root, request))
                MediaProtocol.DASHSCOPE_VIDEO -> unknownResponse()
            }
        }
    }

    override suspend fun poll(
        profile: LlmProfile,
        selection: MediaModelSelection,
        jobId: String,
        kind: MediaKind,
    ): MediaPollResult {
        validate(profile, selection, kind, requireEnabled = false)
        if (!isAsync(selection) || !validJobId(jobId)) {
            throw failure(MediaFailureKind.VALIDATION, "Задание генерации не найдено.")
        }
        val url = endpoint(profile, selection, "tasks/$jobId")
        return attempt(submitting = false) {
            val response = client.get(url) {
                timeout { requestTimeoutMillis = POLL_TIMEOUT_MS }
                authenticate(profile)
            }
            val root = responseObject(response, submitting = false)
            rejectProviderError(root)
            val output = root.obj("output") ?: invalidResponse()
            // Some gateways omit task_id; when supplied it must belong to the requested job.
            if (output.string("task_id")?.let { it != jobId } == true) invalidResponse()
            when (output.string("task_status")) {
                "PENDING", "RUNNING", "SUSPENDED" -> MediaPollResult.Pending
                "SUCCEEDED" -> {
                    val mediaUrl = if (kind == MediaKind.VIDEO) output.string("video_url") else imageUrl(root)
                    if (mediaUrl.isNullOrBlank()) invalidResponse()
                    checkedOutputUrl(mediaUrl)
                    val usage = root.obj("usage")
                    val dimensions = usage?.string("size")?.split('*', '×')
                    MediaPollResult.Completed(MediaRemoteOutput(
                        kind = kind, url = mediaUrl,
                        width = usage?.number("width")?.toInt() ?: dimensions?.getOrNull(0)?.toIntOrNull() ?: 0,
                        height = usage?.number("height")?.toInt() ?: dimensions?.getOrNull(1)?.toIntOrNull() ?: 0,
                        durationSeconds = usage?.number("output_video_duration") ?: usage?.number("duration")
                            ?: usage?.number("video_duration"),
                    ))
                }
                "FAILED", "CANCELED", "CANCELLED" -> MediaPollResult.Failed(
                    providerFailure(output.string("code"), "Сервис не смог создать файл. Измените запрос или повторите позже."),
                )
                "UNKNOWN" -> MediaPollResult.Failed(failure(MediaFailureKind.UNKNOWN_OUTCOME,
                    "Срок хранения задания истёк. Результат генерации неизвестен."))
                else -> invalidResponse()
            }
        }
    }

    override suspend fun download(output: MediaRemoteOutput): DownloadedMedia {
        if (output.url.isBlank() == output.dataBase64.isBlank()) {
            throw failure(MediaFailureKind.DOWNLOAD, "Сервис не вернул файл.")
        }
        return try {
            val limit = if (output.kind == MediaKind.IMAGE) MAX_IMAGE_BYTES else MAX_VIDEO_BYTES
            val bytes = if (output.dataBase64.isNotBlank()) {
                if (output.dataBase64.length.toLong() > (limit.toLong() + 2) / 3 * 4) oversized()
                Base64.decode(output.dataBase64).also { if (it.size > limit) oversized() }
            } else {
                checkedOutputUrl(output.url)
                withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                    // Signed CDN URLs are complete; the provider API key must never be forwarded.
                    client.prepareGet(output.url) {
                        timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS }
                    }.execute { response ->
                        if (!response.status.isSuccess()) {
                            throw failure(MediaFailureKind.DOWNLOAD, "Не удалось скачать созданный файл. Повторите загрузку.", response.status.value)
                        }
                        response.readBounded(limit)
                    }
                } ?: throw failure(MediaFailureKind.DOWNLOAD, "Загрузка файла заняла слишком много времени. Повторите загрузку.")
            }
            val mime = detectMediaType(bytes, output.kind)
                ?: throw failure(MediaFailureKind.DOWNLOAD, "Сервис вернул повреждённый файл или неподдерживаемый формат.")
            DownloadedMedia(bytes, mime, output.width, output.height, output.durationSeconds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaGatewayException) {
            throw e
        } catch (e: Exception) {
            throw MediaGatewayException(MediaFailureKind.DOWNLOAD, "Не удалось скачать созданный файл. Повторите загрузку.", cause = e)
        }
    }

    private fun payload(selection: MediaModelSelection, request: MediaGenerationRequest): JsonObject = buildJsonObject {
        put("model", selection.modelId.trim())
        when (selection.protocol) {
            MediaProtocol.OPENAI_IMAGES -> {
                put("prompt", request.prompt)
                put("n", 1)
                put("size", "${request.width}x${request.height}")
                // GPT Image rejects response_format; other Images-compatible models may return URLs.
                if (selection.modelId.startsWith("gpt-image-")) put("output_format", "png")
            }
            MediaProtocol.DASHSCOPE_IMAGE -> {
                putJsonObject("input") {
                    if (legacyAsyncImage(selection.modelId)) put("prompt", request.prompt)
                    else putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("content") { addJsonObject { put("text", request.prompt) } }
                        }
                    }
                }
                putJsonObject("parameters") {
                    put("size", "${request.width}*${request.height}")
                    put("n", 1)
                    put("prompt_extend", false)
                }
            }
            MediaProtocol.DASHSCOPE_VIDEO -> {
                putJsonObject("input") { put("prompt", request.prompt) }
                putJsonObject("parameters") {
                    if (selection.modelId.startsWith("wan2.7-")) {
                        val ratio = when (request.width.toDouble() / request.height) {
                            in 1.75..1.80 -> "16:9"
                            in 0.55..0.57 -> "9:16"
                            in 1.32..1.34 -> "4:3"
                            in 0.74..0.76 -> "3:4"
                            in 0.99..1.01 -> "1:1"
                            else -> throw failure(MediaFailureKind.VALIDATION, "Выберите поддерживаемые пропорции видео.")
                        }
                        put("resolution", if (minOf(request.width, request.height) >= 1080) "1080P" else "720P")
                        put("ratio", ratio)
                    } else put("size", "${request.width}*${request.height}")
                    put("duration", request.durationSeconds)
                    put("prompt_extend", false)
                }
            }
        }
    }

    private fun openAiOutput(root: JsonObject, request: MediaGenerationRequest): MediaRemoteOutput {
        val first = (root["data"] as? JsonArray)?.firstOrNull() as? JsonObject ?: unknownResponse()
        val b64 = first.string("b64_json").orEmpty()
        val url = first.string("url").orEmpty()
        if (b64.isBlank() && url.isBlank()) unknownResponse()
        if (url.isNotBlank()) checkedOutputUrl(url)
        return MediaRemoteOutput(request.kind, url = if (b64.isBlank()) url else "", dataBase64 = b64,
            width = request.width, height = request.height)
    }

    private fun qwenOutput(root: JsonObject, request: MediaGenerationRequest): MediaRemoteOutput {
        val url = imageUrl(root) ?: unknownResponse()
        checkedOutputUrl(url)
        return MediaRemoteOutput(request.kind, url = url, width = request.width, height = request.height)
    }

    private fun imageUrl(root: JsonObject): String? {
        val output = root.obj("output") ?: return null
        val choices = output["choices"] as? JsonArray
        val messageImage = choices.orEmpty().asSequence()
            .mapNotNull { (it as? JsonObject)?.obj("message")?.get("content") as? JsonArray }
            .flatMap { it.asSequence() }.mapNotNull { (it as? JsonObject)?.string("image") }
            .firstOrNull { it.isNotBlank() }
        return messageImage ?: (output["results"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("url") }.firstOrNull { it.isNotBlank() }
    }

    private fun legacyAsyncImage(model: String) = model == "qwen-image" || model == "qwen-image-plus"
    private fun isAsync(selection: MediaModelSelection) = selection.protocol == MediaProtocol.DASHSCOPE_VIDEO ||
        (selection.protocol == MediaProtocol.DASHSCOPE_IMAGE &&
            (legacyAsyncImage(selection.modelId) || selection.modelId.startsWith("qwen-image-3.0")))

    private fun validate(profile: LlmProfile, selection: MediaModelSelection, kind: MediaKind, requireEnabled: Boolean = true) {
        if (requireEnabled && !profile.enabled || profile.id != selection.profileId || selection.modelId.isBlank() ||
            profile.provider == ProviderType.OPENAI_SUBSCRIPTION ||
            (kind == MediaKind.VIDEO) != (selection.protocol == MediaProtocol.DASHSCOPE_VIDEO)) {
            throw failure(MediaFailureKind.UNAVAILABLE, "Выберите доступное подключение и модель для генерации.")
        }
    }

    private fun endpoint(profile: LlmProfile, selection: MediaModelSelection, suffix: String): String {
        val base = selection.baseUrl.ifBlank { suggestedMediaBaseUrl(profile, selection.protocol) }.trim().trimEnd('/')
        val parsed = try { Url(base) } catch (e: Exception) {
            throw MediaGatewayException(MediaFailureKind.VALIDATION, "Проверьте адрес API генерации.", cause = e)
        }
        if (parsed.protocol.name !in setOf("https", "http") || parsed.host.isBlank() ||
            parsed.user != null || parsed.password != null || parsed.encodedQuery.isNotBlank() || parsed.fragment.isNotBlank()) {
            throw failure(MediaFailureKind.VALIDATION, "Проверьте адрес API генерации.")
        }
        return if (base.endsWith("/$suffix")) base else "$base/$suffix"
    }

    private fun HttpRequestBuilder.authenticate(profile: LlmProfile) {
        val key = profile.apiKey.trim()
        if (key.isBlank()) return
        when (profile.authType) {
            LlmAuthType.BEARER -> header(HttpHeaders.Authorization, "Bearer $key")
            LlmAuthType.X_API_KEY -> header("x-api-key", key)
            LlmAuthType.QUERY_KEY -> parameter("key", key)
            null -> Unit
        }
    }

    private suspend fun responseObject(response: HttpResponse, submitting: Boolean): JsonObject {
        val status = response.status.value
        if (!response.status.isSuccess()) {
            var diagnosticCause: Throwable? = null
            val providerCode = try {
                val error = json.parseToJsonElement(response.readBounded(64 * 1024).decodeToString()) as? JsonObject
                error?.string("code") ?: error?.obj("error")?.string("code")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A non-JSON proxy error remains an explicit HTTP failure, retaining the decode cause.
                diagnosticCause = e
                null
            }
            val knownProviderFailure = providerFailure(providerCode, "")
            val error = when {
                status == 401 || status == 403 -> failure(MediaFailureKind.AUTHENTICATION, "Проверьте API-ключ и права доступа.", status)
                status == 404 && !submitting -> failure(MediaFailureKind.UNKNOWN_OUTCOME,
                    "Сохранённое задание больше недоступно. Результат генерации неизвестен.", status)
                status == 404 -> failure(MediaFailureKind.UNAVAILABLE, "API генерации или модель недоступны. Проверьте настройки.", status)
                submitting && (status >= 500 || status == 408) -> failure(MediaFailureKind.UNKNOWN_OUTCOME,
                    "Сервис не подтвердил результат. Автоматический повтор может создать дубликат.", status)
                status == 429 || status >= 500 || status == 408 -> failure(MediaFailureKind.TRANSIENT, "Сервис временно недоступен. Повторите позже.", status)
                knownProviderFailure.kind == MediaFailureKind.UNAVAILABLE -> failure(MediaFailureKind.UNAVAILABLE,
                    "Модель недоступна. Проверьте настройки генерации.", status)
                knownProviderFailure.kind == MediaFailureKind.AUTHENTICATION -> failure(MediaFailureKind.AUTHENTICATION,
                    "Проверьте API-ключ и права доступа.", status)
                else -> failure(MediaFailureKind.REJECTED, "Сервис отклонил запрос генерации. Проверьте параметры.", status)
            }
            throw MediaGatewayException(error.kind, error.message.orEmpty(), status, diagnosticCause)
        }
        val bytes = response.readBounded(MAX_RESPONSE_BYTES)
        return try { json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: error("Non-object response") }
        catch (e: Exception) {
            throw MediaGatewayException(if (submitting) MediaFailureKind.UNKNOWN_OUTCOME else MediaFailureKind.INVALID_RESPONSE,
                "Сервис вернул неожиданный ответ. Результат генерации не подтверждён.", cause = e)
        }
    }

    private suspend fun <T : Any> attempt(submitting: Boolean, action: suspend () -> T): T = try {
        withTimeoutOrNull(if (submitting) SUBMIT_TIMEOUT_MS else POLL_TIMEOUT_MS) { action() }
            ?: throw failure(if (submitting) MediaFailureKind.UNKNOWN_OUTCOME else MediaFailureKind.TRANSIENT,
                if (submitting) "Время ожидания истекло. Результат генерации неизвестен." else "Не удалось узнать состояние генерации. Повторите позже.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: MediaGatewayException) {
        // A completed POST whose response cannot be consumed has an unknown outcome.
        if (submitting && e.kind == MediaFailureKind.DOWNLOAD) {
            throw MediaGatewayException(MediaFailureKind.UNKNOWN_OUTCOME, "Ответ генерации не удалось прочитать. Результат неизвестен.", cause = e)
        }
        throw e
    } catch (e: Exception) {
        throw MediaGatewayException(if (submitting) MediaFailureKind.UNKNOWN_OUTCOME else MediaFailureKind.TRANSIENT,
            if (submitting) "Связь прервалась. Результат генерации неизвестен." else "Не удалось узнать состояние генерации. Повторите позже.", cause = e)
    }

    private fun rejectProviderError(root: JsonObject) {
        val code = root.string("code")?.takeIf { it.isNotBlank() }
        if (root["error"] != null && root["error"] != JsonNull || code != null) {
            throw providerFailure(code ?: root.obj("error")?.string("code"), "Сервис отклонил запрос генерации. Проверьте настройки и параметры.")
        }
    }

    private fun providerFailure(code: String?, message: String): MediaGatewayException = failure(when (code) {
        "InvalidApiKey", "InvalidApiKeyError", "AccessDenied", "Unauthorized", "invalid_api_key" -> MediaFailureKind.AUTHENTICATION
        "ModelNotFound", "InvalidModel", "model_not_found" -> MediaFailureKind.UNAVAILABLE
        "Throttling", "Throttling.RateQuota", "TooManyRequests", "rate_limit_exceeded" -> MediaFailureKind.TRANSIENT
        else -> MediaFailureKind.REJECTED
    }, message)

    private fun checkedOutputUrl(value: String) {
        val url = try { Url(value) } catch (e: Exception) {
            throw MediaGatewayException(MediaFailureKind.DOWNLOAD, "Сервис вернул неверный адрес файла.", cause = e)
        }
        if (url.protocol.name !in setOf("https", "http") || url.host.isBlank() || url.user != null || url.password != null) {
            throw failure(MediaFailureKind.DOWNLOAD, "Сервис вернул неверный адрес файла.")
        }
    }

    private suspend fun HttpResponse.readBounded(limit: Int): ByteArray {
        if ((headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > limit) oversized()
        val channel = bodyAsChannel()
        val chunks = mutableListOf<ByteArray>()
        var size = 0
        while (true) {
            val chunk = ByteArray(minOf(16_384, limit - size + 1))
            val count = channel.readAvailable(chunk, 0, chunk.size)
            if (count < 0) break
            if (count == 0) continue
            size += count
            if (size > limit) oversized()
            chunks += if (count == chunk.size) chunk else chunk.copyOf(count)
        }
        val bytes = ByteArray(size)
        var offset = 0
        for (chunk in chunks) { chunk.copyInto(bytes, offset); offset += chunk.size }
        return bytes
    }

    private fun validJobId(value: String): Boolean = value.length in 1..200 && value.all { it.isLetterOrDigit() || it in "-_" }
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull
    private fun failure(kind: MediaFailureKind, message: String, status: Int? = null) = MediaGatewayException(kind, message, status)
    private fun unknownResponse(): Nothing = throw failure(MediaFailureKind.UNKNOWN_OUTCOME, "Сервис не вернул результат генерации. Автоматический повтор отключён.")
    private fun invalidResponse(): Nothing = throw failure(MediaFailureKind.INVALID_RESPONSE, "Сервис вернул неожиданный статус генерации.")
    private fun oversized(): Nothing = throw failure(MediaFailureKind.DOWNLOAD, "Созданный файл превышает допустимый размер.")

    private companion object {
        const val SUBMIT_TIMEOUT_MS = 300_000L
        const val POLL_TIMEOUT_MS = 30_000L
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
        const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
        const val MAX_VIDEO_BYTES = 256 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 48 * 1024 * 1024
    }
}

/** Do not save an HTML error page under an image/video extension even after HTTP 200. */
internal fun detectMediaType(bytes: ByteArray, kind: MediaKind): String? {
    fun ascii(offset: Int, text: String) = bytes.size >= offset + text.length &&
        text.indices.all { bytes[offset + it].toInt() and 255 == text[it].code }
    return when (kind) {
        MediaKind.IMAGE -> when {
            bytes.size >= 8 && bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
            bytes.size >= 3 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 255 == 216 && bytes[2].toInt() and 255 == 255 -> "image/jpeg"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            else -> null
        }
        MediaKind.VIDEO -> if (ascii(4, "ftyp")) "video/mp4" else null
    }
}
