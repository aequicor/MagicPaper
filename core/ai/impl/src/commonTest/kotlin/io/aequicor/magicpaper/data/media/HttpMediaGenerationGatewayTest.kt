package io.aequicor.magicpaper.data.media

import io.aequicor.magicpaper.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HttpMediaGenerationGatewayTest {
    private val profile = LlmProfile(id = "media", name = "Media", baseUrl = "https://chat.example/v1", apiKey = "private-key",
        advanced = AdvancedLlmOptions(temperature = 1.2, extraParameters = mapOf("secret_chat_parameter" to JsonPrimitive(true))))
    private val imageRequest = MediaGenerationRequest(MediaKind.IMAGE, "diagram", width = 1024, height = 1024)
    private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
    private fun selection(protocol: MediaProtocol, model: String = "gpt-image-1.5") =
        MediaModelSelection(profile.id, model, protocol, "https://media.example/api/v1")
    private fun TestScope.client(handler: MockRequestHandler) = HttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = UnconfinedTestDispatcher(testScheduler)
        addHandler(handler)
    }))

    @Test fun openAiUsesMediaEndpointAndNoChatParametersThenDecodesBytes() = runTest {
        val client = client { request ->
            assertEquals("https://media.example/api/v1/images/generations", request.url.toString())
            assertEquals("Bearer private-key", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(setOf("model", "prompt", "n", "size", "output_format"), body.keys)
            assertEquals("1024x1024", body["size"]!!.jsonPrimitive.content)
            assertEquals("png", body["output_format"]!!.jsonPrimitive.content)
            respond("""{"data":[{"b64_json":"${Base64.encode(png)}"}]}""")
        }
        try {
            val gateway = HttpMediaGenerationGateway(client, Json)
            val output = assertIs<MediaSubmission.Completed>(gateway.submit(profile, selection(MediaProtocol.OPENAI_IMAGES), imageRequest)).output
            val result = gateway.download(output)
            assertContentEquals(png, result.bytes)
            assertEquals("image/png", result.mimeType)
            assertEquals(1024, result.width)
        } finally { client.close() }
    }

    @Test fun legacyQwenUsesAsyncTextToImageAndPersistsTaskIdentity() = runTest {
        var calls = 0
        val client = client { request ->
            calls++
            when (calls) {
                1 -> {
                    assertEquals("/api/v1/services/aigc/text2image/image-synthesis", request.url.encodedPath)
                    assertEquals("enable", request.headers["X-DashScope-Async"])
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("diagram", body["input"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
                    assertEquals("1328*1328", body["parameters"]!!.jsonObject["size"]!!.jsonPrimitive.content)
                    respond("""{"output":{"task_id":"image-job","task_status":"PENDING"}}""")
                }
                2 -> {
                    assertEquals("/api/v1/tasks/image-job", request.url.encodedPath)
                    respond("""{"output":{"task_id":"image-job","task_status":"RUNNING"}}""")
                }
                else -> respond("""{"output":{"task_id":"image-job","task_status":"SUCCEEDED","results":[{"url":"https://cdn.example/image.png?signature=private"}]}}""")
            }
        }
        try {
            val gateway = HttpMediaGenerationGateway(client, Json)
            val selection = selection(MediaProtocol.DASHSCOPE_IMAGE, "qwen-image-plus")
            val submitted = assertIs<MediaSubmission.Accepted>(gateway.submit(profile, selection, imageRequest.copy(width = 1328, height = 1328)))
            assertEquals("image-job", submitted.jobId)
            assertEquals(MediaPollResult.Pending, gateway.poll(profile, selection, submitted.jobId, MediaKind.IMAGE))
            val output = assertIs<MediaPollResult.Completed>(gateway.poll(profile, selection, submitted.jobId, MediaKind.IMAGE)).output
            assertEquals(MediaKind.IMAGE, output.kind)
            assertEquals("image/png", output.mimeType)
            assertFalse(output.toString().contains("private"))
        } finally { client.close() }
    }

    @Test fun qwenThreeUsesNewAsyncEndpointAndMessageResponse() = runTest {
        var first = true
        val client = client { request ->
            if (first) {
                first = false
                assertEquals("/api/v1/services/aigc/image-generation/generation", request.url.encodedPath)
                assertEquals("enable", request.headers["X-DashScope-Async"])
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("diagram", body["input"]!!.jsonObject["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
                respond("""{"output":{"task_id":"qwen-three","task_status":"PENDING"}}""")
            } else respond("""{"output":{"task_status":"SUCCEEDED","choices":[{"message":{"content":[{"image":"https://cdn.example/a.png"}]}}]},"usage":{"width":1024,"height":1024}}""")
        }
        try {
            val gateway = HttpMediaGenerationGateway(client, Json)
            val selection = selection(MediaProtocol.DASHSCOPE_IMAGE, "qwen-image-3.0-pro")
            assertIs<MediaSubmission.Accepted>(gateway.submit(profile, selection, imageRequest))
            val output = assertIs<MediaPollResult.Completed>(gateway.poll(profile, selection, "qwen-three", MediaKind.IMAGE)).output
            assertEquals(1024, output.width)
            assertEquals("https://cdn.example/a.png", output.url)
        } finally { client.close() }
    }

    @Test fun qwenTwoUsesDocumentedSynchronousEndpoint() = runTest {
        val client = client { request ->
            assertEquals("/api/v1/services/aigc/multimodal-generation/generation", request.url.encodedPath)
            assertNull(request.headers["X-DashScope-Async"])
            respond("""{"output":{"choices":[{"message":{"content":[{"image":"https://cdn.example/a.png"}]}}]}}""")
        }
        try {
            val result = HttpMediaGenerationGateway(client, Json).submit(profile, selection(MediaProtocol.DASHSCOPE_IMAGE, "qwen-image-2.0"), imageRequest)
            assertIs<MediaSubmission.Completed>(result)
        } finally { client.close() }
    }

    @Test fun wanUsesAsyncHeaderAndNewResolutionParametersThenReadsDuration() = runTest {
        var first = true
        val client = client { request ->
            if (first) {
                first = false
                assertEquals("/api/v1/services/aigc/video-generation/video-synthesis", request.url.encodedPath)
                assertEquals("enable", request.headers["X-DashScope-Async"])
                val params = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["parameters"]!!.jsonObject
                assertEquals("720P", params["resolution"]!!.jsonPrimitive.content)
                assertEquals("16:9", params["ratio"]!!.jsonPrimitive.content)
                assertEquals(2, params["duration"]!!.jsonPrimitive.int)
                assertNull(params["size"])
                respond("""{"output":{"task_id":"wan-job","task_status":"PENDING"}}""")
            } else respond("""{"output":{"task_id":"wan-job","task_status":"SUCCEEDED","video_url":"https://cdn.example/video.mp4"},"usage":{"output_video_duration":2,"size":"1280*720"}}""")
        }
        try {
            val gateway = HttpMediaGenerationGateway(client, Json)
            val selection = selection(MediaProtocol.DASHSCOPE_VIDEO, "wan2.7-t2v")
            val request = MediaGenerationRequest(MediaKind.VIDEO, "animation", width = 1280, height = 720, durationSeconds = 2)
            val submitted = assertIs<MediaSubmission.Accepted>(gateway.submit(profile, selection, request))
            val output = assertIs<MediaPollResult.Completed>(gateway.poll(profile, selection, submitted.jobId, MediaKind.VIDEO)).output
            assertEquals("video/mp4", output.mimeType)
            assertEquals(1280, output.width)
            assertEquals(2.0, output.durationSeconds)
        } finally { client.close() }
    }

    @Test fun downloadNeverForwardsApiCredentialsOrTrustsAnHtmlSuccess() = runTest {
        var first = true
        val client = client { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["x-api-key"])
            assertNull(request.url.parameters["key"])
            if (first) { first = false; respond(png) } else respond("<html>private</html>")
        }
        try {
            val gateway = HttpMediaGenerationGateway(client, Json)
            val output = MediaRemoteOutput(MediaKind.IMAGE, url = "https://cdn.example/output")
            assertContentEquals(png, gateway.download(output).bytes)
            val error = assertFailsWith<MediaGatewayException> { gateway.download(output) }
            assertEquals(MediaFailureKind.DOWNLOAD, error.kind)
            assertFalse(error.message.orEmpty().contains("private"))
        } finally { client.close() }
    }

    @Test fun localImagesServerWorksWithoutAnApiKeyOrChatModel() = runTest {
        val local = LlmProfile("local", "Local", baseUrl = "http://127.0.0.1:8188/v1", authType = null)
        val client = client { request ->
            assertEquals("http://127.0.0.1:8188/v1/images/generations", request.url.toString())
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["x-api-key"])
            assertNull(request.url.parameters["key"])
            respond("""{"data":[{"url":"http://127.0.0.1:8188/output/image.png"}]}""")
        }
        try {
            val output = HttpMediaGenerationGateway(client, Json).submit(local,
                MediaModelSelection("local", "local-image-model"), imageRequest)
            assertIs<MediaSubmission.Completed>(output)
        } finally { client.close() }
    }

    @Test fun badConfigurationAndJobIdentityNeverReachNetwork() = runTest {
        val client = client { error("Must validate before network") }
        try {
            val gateway = HttpMediaGenerationGateway(client, Json)
            for (bad in listOf(selection(MediaProtocol.OPENAI_IMAGES).copy(baseUrl = "file:///tmp"),
                selection(MediaProtocol.OPENAI_IMAGES).copy(profileId = "someone-else"),
                selection(MediaProtocol.OPENAI_IMAGES).copy(baseUrl = "https://user:private@media.example/v1"))) {
                assertFailsWith<MediaGatewayException> { gateway.submit(profile, bad, imageRequest) }
            }
            assertFailsWith<MediaGatewayException> { gateway.poll(profile, selection(MediaProtocol.DASHSCOPE_VIDEO, "wan2.6-t2v"), "../other-task", MediaKind.VIDEO) }
            assertFailsWith<MediaGatewayException> { gateway.submit(profile.copy(provider = ProviderType.OPENAI_SUBSCRIPTION), selection(MediaProtocol.OPENAI_IMAGES), imageRequest) }
        } finally { client.close() }
    }

    @Test fun rejectedRequestsAndUnknownAcknowledgementsHaveDistinctOutcomes() = runTest {
        val cases = listOf(
            Triple(400, """{"error":{"code":"invalid_prompt","message":"private"}}""", MediaFailureKind.REJECTED),
            Triple(400, """{"error":{"code":"model_not_found"}}""", MediaFailureKind.UNAVAILABLE),
            Triple(401, "private", MediaFailureKind.AUTHENTICATION),
            Triple(403, "private", MediaFailureKind.AUTHENTICATION),
            Triple(404, "private", MediaFailureKind.UNAVAILABLE),
            Triple(429, "private", MediaFailureKind.TRANSIENT),
            Triple(503, "private", MediaFailureKind.UNKNOWN_OUTCOME),
            Triple(200, "not-json-private", MediaFailureKind.UNKNOWN_OUTCOME),
            Triple(200, "{}", MediaFailureKind.UNKNOWN_OUTCOME),
        )
        for ((status, body, expected) in cases) {
            val client = client { respond(body, HttpStatusCode.fromValue(status)) }
            try {
                val failure = assertFailsWith<MediaGatewayException> {
                    HttpMediaGenerationGateway(client, Json).submit(profile, selection(MediaProtocol.OPENAI_IMAGES), imageRequest)
                }
                assertEquals(expected, failure.kind, "HTTP $status")
                assertFalse(failure.message.orEmpty().contains("private"))
            } finally { client.close() }
        }
    }

    @Test fun noAutomaticResubmitAfterDisconnectAndExternalCancellationPropagates() = runTest {
        var calls = 0
        val client = client { calls++; throw IllegalStateException("private transport message") }
        try {
            val failure = assertFailsWith<MediaGatewayException> {
                HttpMediaGenerationGateway(client, Json).submit(profile, selection(MediaProtocol.OPENAI_IMAGES), imageRequest)
            }
            assertTrue(failure.outcomeUnknown)
            assertEquals(1, calls)
            assertFalse(failure.message.orEmpty().contains("private"))
        } finally { client.close() }
        val waiting = client { awaitCancellation() }
        try {
            val gateway = HttpMediaGenerationGateway(waiting, Json)
            assertFailsWith<CancellationException> {
                withTimeout(10) { gateway.submit(profile, selection(MediaProtocol.OPENAI_IMAGES), imageRequest) }
            }
            val ownTimeout = assertFailsWith<MediaGatewayException> {
                gateway.submit(profile, selection(MediaProtocol.OPENAI_IMAGES), imageRequest)
            }
            assertTrue(ownTimeout.outcomeUnknown)
        } finally { waiting.close() }
    }

    @Test fun providerErrorsInSuccessfulHttpAndWrongTaskResultDoNotPass() = runTest {
        val responses = ArrayDeque(listOf(
            """{"code":"InvalidApiKey","message":"private-key"}""",
            """{"output":{"task_id":"wrong-task","task_status":"SUCCEEDED","video_url":"https://cdn.example/v.mp4"}}""",
            """{"output":{"task_id":"wan-job","task_status":"FAILED","code":"DataInspectionFailed","message":"private-prompt"}}""",
            """{"output":{"task_id":"wan-job","task_status":"UNKNOWN"}}""",
        ))
        val client = client { respond(responses.removeFirst()) }
        try {
            val gateway = HttpMediaGenerationGateway(client, Json)
            val selection = selection(MediaProtocol.DASHSCOPE_VIDEO, "wan2.6-t2v")
            assertEquals(MediaFailureKind.AUTHENTICATION, assertFailsWith<MediaGatewayException> {
                gateway.submit(profile, selection, MediaGenerationRequest(MediaKind.VIDEO, "test", width = 1280, height = 720))
            }.kind)
            assertEquals(MediaFailureKind.INVALID_RESPONSE, assertFailsWith<MediaGatewayException> {
                gateway.poll(profile, selection, "wan-job", MediaKind.VIDEO)
            }.kind)
            assertEquals(MediaFailureKind.REJECTED, assertIs<MediaPollResult.Failed>(gateway.poll(profile, selection, "wan-job", MediaKind.VIDEO)).failure.kind)
            assertTrue(assertIs<MediaPollResult.Failed>(gateway.poll(profile, selection, "wan-job", MediaKind.VIDEO)).failure.outcomeUnknown)
        } finally { client.close() }
    }

    @Test fun disablingProfileBlocksNewSubmissionButStillAllowsCheckingAnAcceptedJob() = runTest {
        var calls = 0
        val client = client { calls++; respond("""{"output":{"task_id":"accepted-job","task_status":"RUNNING"}}""") }
        try {
            val gateway = HttpMediaGenerationGateway(client, Json)
            val selected = selection(MediaProtocol.DASHSCOPE_VIDEO, "wan2.6-t2v")
            assertFailsWith<MediaGatewayException> {
                gateway.submit(profile.copy(enabled = false), selected, MediaGenerationRequest(MediaKind.VIDEO, "test"))
            }
            assertEquals(MediaPollResult.Pending, gateway.poll(profile.copy(enabled = false), selected, "accepted-job", MediaKind.VIDEO))
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun missingSavedJobHasUnknownOutcomeInsteadOfClaimingTheModelIsUnavailable() = runTest {
        val client = client { respond("{}", status = HttpStatusCode.NotFound) }
        try {
            val failure = assertFailsWith<MediaGatewayException> {
                HttpMediaGenerationGateway(client, Json).poll(profile,
                    selection(MediaProtocol.DASHSCOPE_VIDEO, "wan2.6-t2v"), "expired-job", MediaKind.VIDEO)
            }
            assertTrue(failure.outcomeUnknown)
            assertEquals(404, failure.statusCode)
        } finally { client.close() }
    }

    @Test fun rejectsOversizedFilesBeforeReadingAndUsesByteSignature() = runTest {
        val client = client { respond("small", headers = headersOf(HttpHeaders.ContentLength, "999999999")) }
        try {
            assertEquals(MediaFailureKind.DOWNLOAD, assertFailsWith<MediaGatewayException> {
                HttpMediaGenerationGateway(client, Json).download(MediaRemoteOutput(MediaKind.IMAGE, url = "https://cdn.example/large"))
            }.kind)
            assertEquals("video/mp4", detectMediaType(byteArrayOf(0, 0, 0, 16) + "ftypisom".encodeToByteArray(), MediaKind.VIDEO))
            assertNull(detectMediaType(png, MediaKind.VIDEO))
            assertNull(detectMediaType("<svg/>".encodeToByteArray(), MediaKind.IMAGE))
        } finally { client.close() }
    }
}
