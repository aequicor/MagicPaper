package io.aequicor.magicpaper.data.coding

import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.domain.EffortSelection
import io.aequicor.magicpaper.domain.ReasoningEffort
import io.aequicor.magicpaper.domain.ModelVariant
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.domain.AdvancedLlmOptions
import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.RuntimePhase
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Интеграционный тест полного цикла: изолированная установка пи-агента и
 * прогон запроса через мок-сервер модели. Требует сети (npm-реестр) и
 * включается переменной окружения MAGICPAPER_PI_IT=true,
 * чтобы сборка оставалась герметичной.
 */
class PiCodingRuntimeIntegrationTest {

    private val enabled = System.getenv("MAGICPAPER_PI_IT") == "true" ||
        System.getProperty("magicpaper.pi.it") == "true"

    @Test
    fun installAndRunWithIsolatedRuntime() {
        if (!enabled) {
            println("Пропущено: включите MAGICPAPER_PI_IT=true")
            return
        }
        val workRoot = createTempDir("magicpaper-pi-it")
        val projectDir = File(workRoot, "project").apply { mkdirs() }
        val requests = CopyOnWriteArrayList<String>()
        val mock = startMockModelServer(requests)
        try {
            val profile = LlmProfile(
                id = "it",
                name = "мок-сервер",
                provider = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "http://127.0.0.1:${mock.address.port}/v1",
                modelId = "mock-model",
                apiKey = "test-key",
                modelLibraryVersion = 1,
                codingModelId = "variant:it",
                variants = listOf(ModelVariant("variant:it", "Custom", "mock-model", AdvancedLlmOptions(temperature = .23, topP = .81, maxTokens = 7000))),
            )
            val runtime = PiCodingRuntime(rootDir = File(workRoot, "coding"))

            val statuses = runBlocking { runtime.ensureReady().toList() }
            val last = statuses.last()
            println("Фазы установки: " + statuses.joinToString(" -> ") { it.phase.toString() })
            assertEquals(RuntimePhase.READY, last.phase, "итог установки: ${last.detail}")

            val project = CodingProject(id = "p1", name = "demo", path = projectDir.absolutePath, createdAt = 1L)
            val session = CodingSession(id = "s1", projectId = "p1", name = "Основная", createdAt = 1L)
            val events = runBlocking { runtime.run(project, session, "Скажи одно слово", profile).toList() }

            assertTrue(events.any { it is CodingEvent.SessionStarted }, "нет заголовка сессии")
            val finals = events.filterIsInstance<CodingEvent.FinalText>()
            assertTrue(finals.isNotEmpty(), "нет финального текста; события: $events")
            assertTrue(finals.last().text.contains("PONG"), "текст: ${finals.last().text}")
            assertTrue(events.last() is CodingEvent.Finished)
            val sent = Json.parseToJsonElement(requests.first()).jsonObject
            assertEquals("mock-model", sent["model"]!!.jsonPrimitive.content)
            assertEquals(.23, sent["temperature"]!!.jsonPrimitive.double)
            assertEquals(.81, sent["top_p"]!!.jsonPrimitive.double)
            assertEquals(7000, sent["max_tokens"]!!.jsonPrimitive.int)

            runBlocking { runtime.uninstall() }
            assertTrue(!File(workRoot, "coding").exists(), "установка не удалена полностью")
        } finally {
            mock.stop(0)
            workRoot.deleteRecursively()
        }
    }

    @Test fun nativeProvidersSendVariantOptions() {
        if (!enabled) return
        val workRoot = createTempDir("magicpaper-native-models-it")
        val projectDir = File(workRoot, "project").apply { mkdirs() }
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            requests += body
            val payload = if (exchange.requestURI.path.endsWith("messages")) {
                listOf(
                    "message_start" to """{"type":"message_start","message":{"id":"msg-test","type":"message","role":"assistant","model":"mock-model","content":[],"usage":{"input_tokens":1,"output_tokens":0}}}""",
                    "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                    "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"PONG"}}""",
                    "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                ).joinToString("\n\n", postfix = "\n\n") { (type, data) -> "event: $type\ndata: $data" }
            } else {
                "data: " + """{"candidates":[{"index":0,"content":{"role":"model","parts":[{"text":"PONG"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}""" + "\n\n"
            }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val runtime = PiCodingRuntime(rootDir = File(workRoot, "coding"))
            assertEquals(RuntimePhase.READY, runBlocking { runtime.ensureReady().toList().last().phase })
            for (provider in listOf(ProviderType.ANTHROPIC, ProviderType.GOOGLE)) {
                val profile = LlmProfile("it", "Test", baseUrl = "http://127.0.0.1:${server.address.port}/v1", apiKey = "test-key", provider = provider,
                    modelId = "mock-model", codingModelId = "variant:it", modelLibraryVersion = 1,
                    variants = listOf(ModelVariant("variant:it", "Custom", "mock-model", AdvancedLlmOptions(temperature = .23, topP = .81, maxTokens = 7000))))
                val events = runBlocking { runtime.run(CodingProject("p", "Test", projectDir.absolutePath, 1), CodingSession(provider.name, "p", "Test", 1), "Say PONG", profile).toList() }
                assertTrue(events.any { it is CodingEvent.FinalText && it.text.contains("PONG") }, "$provider: $events")
                val sent = Json.parseToJsonElement(requests.last()).jsonObject
                val options = if (provider == ProviderType.GOOGLE) sent["generationConfig"]!!.jsonObject else sent
                assertEquals(.23, options["temperature"]!!.jsonPrimitive.double)
                assertEquals(.81, options[if (provider == ProviderType.GOOGLE) "topP" else "top_p"]!!.jsonPrimitive.double)
                assertEquals(7000, options[if (provider == ProviderType.GOOGLE) "maxOutputTokens" else "max_tokens"]!!.jsonPrimitive.int)
            }
            runBlocking { runtime.uninstall() }
        } finally { server.stop(0); workRoot.deleteRecursively() }
    }

    /**
     * Проверка типографики через РЕДАКТИРОВАНИЕ пи-агентом (живой движок).
     *
     * Мок-модель дважды вызывает инструмент edit на файле с «ёлочками»,
     * тире и ё: сначала с испорченным oldText (ASCII-дефис вместо тире —
     * так делает модель-«неряха»), затем с дословным. До защиты кодировки
     * первая попытка уходила в fuzzy-режим и переписывала ВЕСЬ файл из
     * нормализованной копии (тире и кавычки деградировали молча); после —
     * обязана дать честную ошибку, а дословная правка не тронуть соседние строки.
     */
    @Test
    fun editToolPreservesRussianTypography() {
        if (!enabled) {
            println("Пропущено: включите MAGICPAPER_PI_IT=true")
            return
        }
        val workRoot = createTempDir("magicpaper-pi-edit-it")
        val projectDir = File(workRoot, "project").apply { mkdirs() }
        val notes = File(projectDir, "notes.txt")
        val original =
            "Глава первая — «Шалость удалась»\n" +
                "Ёж бежал по пергаменту.\n" +
                "Счёт: 42\n" +
                "Итог — «магическая бумага», а не просто лист.\n"
        notes.writeText(original, Charsets.UTF_8)
        val mock = startEditMockModelServer()
        try {
            val profile = LlmProfile(
                id = "it",
                name = "мок-сервер",
                provider = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "http://127.0.0.1:${mock.address.port}/v1",
                modelId = "mock-model",
                apiKey = "test-key",
            )
            val runtime = PiCodingRuntime(rootDir = File(workRoot, "coding"))
            val statuses = runBlocking { runtime.ensureReady().toList() }
            assertEquals(RuntimePhase.READY, statuses.last().phase, "итог установки: ${statuses.last().detail}")

            val project = CodingProject(id = "p1", name = "demo", path = projectDir.absolutePath, createdAt = 1L)
            val session = CodingSession(id = "s1", projectId = "p1", name = "Основная", createdAt = 1L)
            val events = runBlocking { runtime.run(project, session, "Поправь итог в notes.txt", profile).toList() }

            // Первая попытка: oldText с ASCII-дефисом вместо тире. Патченный бандл не должен ни применять её через fuzzy, ни портить файл.
            val edits = events.filterIsInstance<CodingEvent.ToolFinished>().filter { it.tool == "edit" }
            assertTrue(edits.size >= 2, "ожидали минимум два вызова edit; события: $events")
            assertTrue(edits.first().isError, "первая правка обязана завершиться ошибкой несовпадения: ${edits.first()}")
            assertFalse(
                "Итог - «магическая бумага»." in notes.readText(Charsets.UTF_8),
                "испорченная правка с дефисом не должна была примениться",
            )

            // Итог: дословная правка применена, типографика всех остальных строк цела.
            val expected =
                "Глава первая — «Шалость удалась»\n" +
                    "Ёж бежал по пергаменту.\n" +
                    "Счёт: 42\n" +
                    "Итог — «магическая бумага» и чернила.\n"
            assertEquals(expected, notes.readText(Charsets.UTF_8))

            runBlocking { runtime.uninstall() }
        } finally {
            mock.stop(0)
            workRoot.deleteRecursively()
        }
    }

    /**
     * Регресс самого бага: «Заклинание не сработало: Агент завершился без ответа»
     * при пустом ходе, сгоревшем в лимите вывода (`finish_reason: "length"`).
     * Живой движок pi и мок сервера модели — рантайм обязан: заметить обрезку,
     * сказать о ней в ленту, продолжить ТУ ЖЕ pi-сессию и дойти до ответа без
     * `Failed`. Проверка на проводе закрепляет причину: переключатель мышления и
     * потолок вывода из конфига реально уходят в запрос (без них модель и молчала).
     *
     * Нужен установочный префикс приложения — он копируется во временный каталог,
     * чтобы тест не трогал рабочую установку, и потому тест под тем же флагом
     * MAGICPAPER_PI_IT, что и остальные интеграционные проверки.
     */
    @Test
    fun truncatedEmptyAnswerIsContinuedInSameSession() {
        if (!enabled) {
            println("Пропущено: включите MAGICPAPER_PI_IT=true")
            return
        }
        val installedPrefix = File(System.getProperty("user.home"), ".MagicPaper/coding/prefix")
        assertTrue(installedPrefix.isDirectory, "нет установленного движка pi: $installedPrefix")
        val workRoot = createTempDir("magicpaper-pi-truncation-it")
        val projectDir = File(workRoot, "project").apply { mkdirs() }
        val requests = CopyOnWriteArrayList<String>()
        val mock = startLengthCappedMockServer(requests)
        try {
            installedPrefix.copyRecursively(File(workRoot, "coding/prefix"), overwrite = true)
            val runtime = PiCodingRuntime(rootDir = File(workRoot, "coding"))
            val statuses = runBlocking { runtime.ensureReady().toList() }
            assertEquals(RuntimePhase.READY, statuses.last().phase, "итог установки: ${statuses.last().detail}")

            val profile = LlmProfile(
                id = "it",
                name = "мок-сервер",
                provider = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "http://127.0.0.1:${mock.address.port}/v1",
                modelId = "qwen3.8-flash",
                apiKey = "test-key",
                modelLibraryVersion = 1,
                codingModelId = "variant:reasoning",
                effort = EffortSelection.of(ReasoningEffort.MEDIUM),
                variants = listOf(ModelVariant("variant:reasoning", "Reasoning", "qwen3.8-flash", AdvancedLlmOptions(maxTokens = 16_384, contextLimit = 128_000))),
            )
            val project = CodingProject(id = "p1", name = "demo", path = projectDir.absolutePath, createdAt = 1L)
            val session = CodingSession(id = "s1", projectId = "p1", name = "Основная", createdAt = 1L)
            val events = runBlocking { runtime.run(project, session, "Скажи одно слово", profile).toList() }

            // Пустой ход в лимите — событие обрезки, а не потеря ответа.
            val truncated = events.filterIsInstance<CodingEvent.OutputTruncated>()
            assertTrue(truncated.isNotEmpty(), "нет события обрезки; события: $events")
            assertEquals(8192, truncated.first().outputTokens, "счётчик вывода читается из usage")
            assertEquals(8192, truncated.first().reasoningTokens, "счётчик рассуждения читается из usage")
            // О обрезке сказано в ленте, и прогон продолжен в той же сессии.
            assertTrue(
                events.filterIsInstance<CodingEvent.Notice>().any { "продолжаю прогон" in it.message },
                "нет предупреждения о автопродолжении: $events",
            )
            // Финал — ответ, а не «без ответа».
            assertEquals("ПРОДОЛЖИЛ ответ", events.filterIsInstance<CodingEvent.FinalText>().last().text)
            assertTrue(events.none { it is CodingEvent.Failed }, "прогон не должен завершаться ошибкой: $events")
            assertTrue(events.last() is CodingEvent.Finished)

            // Причина обрезки на проводе: запрос несёт явный переключатель мышления
            // (для локального сервера — chat_template_kwargs, для наружного —
            // enable_thinking + reasoning_effort) и потолок вывода из конфига.
            assertTrue(requests.size >= 2, "сервер не принял второй запрос: ${requests.size}")
            val first = requests.first()
            assertTrue("enable_thinking" in first, "переключатель мышления не доехал до сервера: $first")
            val ceiling = Regex("\"max(?:_completion)?_tokens\":(\\d+)").find(first)?.groupValues?.get(1)
            assertEquals("16384", ceiling, "потолок вывода обязан прийти из конфига")
            // Автопродолжение — та же pi-сессия: исходный запрос виден во второй истории.
            assertTrue("Скажи одно слово" in requests[1], "продолжение потеряло контекст сессии")
        } finally {
            mock.stop(0)
            workRoot.deleteRecursively()
        }
    }

    /**
     * Мок сервера, который первый ход обрывает на лимите токенов с пустым телом
     * (так делает Qwen, когда рассуждение съедает весь вывод), а дальше отвечает.
     * Тела запросов сохраняются для проверки того, что реально ушло на провод.
     */
    private fun startLengthCappedMockServer(requests: CopyOnWriteArrayList<String>): HttpServer {
        val requestNo = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            requests += body
            val truncated = requestNo.getAndIncrement() == 0
            val chunks = if (truncated) {
                listOf(
                    """{"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"length"}],""" +
                        """"usage":{"prompt_tokens":1200,"completion_tokens":8192,""" +
                        """"completion_tokens_details":{"reasoning_tokens":8192}}}""",
                )
            } else {
                listOf(
                    """{"choices":[{"index":0,"delta":{"role":"assistant","content":"ПРОДОЛЖИЛ "}}]}""",
                    """{"choices":[{"index":0,"delta":{"content":"ответ"}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],""" +
                        """"usage":{"prompt_tokens":1300,"completion_tokens":40}}""",
                )
            }
            val payload = chunks.joinToString("\n\n") { "data: $it" } + "\n\ndata: [DONE]\n\n"
            val data = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        return server
    }

    /**
     * Мок-сервер для теста типографики: запрос 0 — tool_call edit с испорченным
     * oldText (дефис вместо тире), запрос 1 — тот же edit с дословным текстом,
     * дальше — финальный текст. Формат — стандартные стриминговые tool_calls.
     */
    private fun startEditMockModelServer(): HttpServer {
        val requestNo = AtomicInteger(0)
        val badEditArgs = editArgsJson(
            oldText = "Итог - «магическая бумага», а не просто лист.",
            newText = "Итог - «магическая бумага».",
        )
        val goodEditArgs = editArgsJson(
            oldText = "Итог — «магическая бумага», а не просто лист.",
            newText = "Итог — «магическая бумага» и чернила.",
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readBytes() // вычитываем тело до ответа
            val no = requestNo.getAndIncrement()
            when {
                no <= 1 -> {
                    val callId = "call_edit_$no"
                    val args = if (no == 0) badEditArgs else goodEditArgs
                    val escaped = args.replace("\\", "\\\\").replace("\"", "\\\"")
                    val chunks = listOf(
                        """{"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"$callId","type":"function","function":{"name":"edit","arguments":""}}]},"index":0}]}""",
                        """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"$escaped"}}]},"index":0}]}""",
                        """{"choices":[{"delta":{},"finish_reason":"tool_calls","index":0}]}""",
                    )
                    val payload = chunks.joinToString("\n\n", prefix = "", postfix = "") { "data: $it" } +
                        "\n\ndata: [DONE]\n\n"
                    val data = payload.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, data.size.toLong())
                    exchange.responseBody.use { it.write(data) }
                }
                else -> {
                    val data = """{"id":"c$no","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"PONG: типографика цела"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                        .toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, data.size.toLong())
                    exchange.responseBody.use { it.write(data) }
                }
            }
        }
        server.start()
        return server
    }

    /** Аргументы инструмента edit; кириллица и «ёлочки» в JSON не требуют экранирования. */
    private fun editArgsJson(oldText: String, newText: String): String =
        "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"$oldText\",\"newText\":\"$newText\"}]}"

    /** Мок OpenAI-совместимого сервера со стримингом SSE. */
    private fun startMockModelServer(requests: MutableList<String>? = null): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            requests?.add(body)
            val stream = "\"stream\":true" in body.replace(" ", "")
            val chunks = listOf(
                """{"choices":[{"delta":{"role":"assistant"},"index":0}]}""",
                """{"choices":[{"delta":{"content":"PONG: протокол "},"index":0}]}""",
                """{"choices":[{"delta":{"content":"работает"},"index":0}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop","index":0}]}""",
            )
            if (stream) {
                val payload = chunks.joinToString("\n\n", prefix = "", postfix = "") { "data: $it" } +
                    "\n\ndata: [DONE]\n\n"
                val data = payload.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            } else {
                val data = """{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"PONG: протокол работает"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                    .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            }
        }
        server.start()
        return server
    }

    private fun createTempDir(prefix: String): File =
        kotlin.io.path.createTempDirectory(prefix).toFile()
}
