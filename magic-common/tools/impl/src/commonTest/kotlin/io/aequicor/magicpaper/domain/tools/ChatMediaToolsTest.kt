package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMediaToolsTest {
    private val args = buildJsonObject { put("prompt", "Draw a tree") }
    private val chat = ChatSession("chat", "Chat", 1, 1)
    private val search = object : SearchEngine {
        override val provider = SearchProvider.AUTO
        override val displayName = "Search"
        override fun isConfigured(settings: AppSettings) = true
        override suspend fun search(query: String, settings: AppSettings, limit: Int) = emptyList<SearchHit>()
    }
    private fun factory(receipts: ToolReceiptStore, owner: MediaToolReceiptOwner, media: MediaGenerationService,
        allowed: suspend (ChatSession, MediaKind) -> Boolean = { _, _ -> true }) = DefaultChatToolSessions(
        search, { emptyList() }, { emptyList() }, { emptyList() }, media,
        DefaultRuntimeQuestionnaireService(InMemoryEventJournal(), "test"), receipts, owner, allowMedia = allowed)

    @Test fun constructorTerminalPortAndAwaiterAgreeOnTheExactSuccessfulReceipt() = runTest {
        val receipts = MemoryToolReceiptStore()
        val owner = DefaultMediaToolReceiptOwner(receipts) { "/image.png" }
        val media = MediaFixture(owner)
        val tools = factory(receipts, owner, media).create(chat, "request", AppSettings(), false)
        val events = mutableListOf<ToolEvent>()
        tools.events.observe { events += it }
        val result = tools.call("image", "magicpaper_image_generate", args)
        assertEquals(result, tools.receipt("image")?.result)
        assertEquals(ToolPhase.SUCCEEDED, tools.receipt("image")?.phase)
        assertEquals(MediaPhase.READY, events.last().media?.phase)
        assertNull(media.startedOwner?.projectId)
        assertEquals("chat", media.startedOwner?.sessionId)
        assertEquals(1, media.submissions)
        assertEquals(result, tools.call("image", "magicpaper_image_generate", args))
        assertEquals(1, media.submissions)
    }

    @Test fun lateCompletionSettlesCancelledProviderAwaiterWithoutResubmission() = runTest {
        val receipts = MemoryToolReceiptStore()
        val owner = DefaultMediaToolReceiptOwner(receipts) { "/image.png" }
        val media = MediaFixture(owner, wait = true)
        val tools = factory(receipts, owner, media).create(chat, "request", AppSettings(), false)
        val call = launch { tools.call("image", "magicpaper_image_generate", args) }
        runCurrent(); call.cancelAndJoin()
        assertEquals(ToolPhase.UNKNOWN, tools.receipt("image")?.phase)
        media.finish()
        assertEquals(ToolPhase.SUCCEEDED, tools.receipt("image")?.phase)
        val recovered = tools.call("image", "magicpaper_image_generate", args)
        assertEquals(tools.receipt("image")?.result, recovered)
        assertEquals(1, media.submissions)
    }

    @Test fun currentMediaPolicyIsCheckedAgainBeforeSubmission() = runTest {
        val receipts = MemoryToolReceiptStore()
        val owner = DefaultMediaToolReceiptOwner(receipts) { null }
        val media = MediaFixture(owner)
        var enabled = true
        val tools = factory(receipts, owner, media) { _, kind -> enabled && kind == MediaKind.IMAGE }
            .create(chat, "request", AppSettings(), false)
        assertTrue(tools.definitions.any { it.id == "image.generate" })
        assertFalse(tools.definitions.any { it.id == "video.generate" })
        assertTrue(tools.definitions.none { it.native || it.id.startsWith("file.") || it.id.startsWith("plan.") })
        enabled = false
        assertFailsWith<ToolStateRejection> { tools.call("image", "magicpaper_image_generate", args) }
        assertEquals(0, media.submissions)
    }

    private class MediaFixture(private val terminal: MediaToolReceiptOwner, private val wait: Boolean = false) : MediaGenerationService {
        override val state = MutableStateFlow<Map<MediaKind, MediaConnectionStatus>>(emptyMap())
        override val operations = MutableStateFlow<Map<String, GeneratedMedia>>(emptyMap())
        var submissions = 0
        var startedOwner: MediaGenerationOwner? = null
        private var operation = ""
        override suspend fun available(kind: MediaKind) = true
        override suspend fun generate(owner: MediaGenerationOwner, operationId: String, request: MediaGenerationRequest,
            authorize: suspend () -> Unit, onUpdate: suspend (GeneratedMedia) -> Unit): GeneratedMedia {
            authorize()
            submissions++
            startedOwner = owner; operation = operationId
            onUpdate(GeneratedMedia(owner.callId, request.kind, MediaPhase.GENERATING))
            if (wait) awaitCancellation()
            return finish()
        }
        suspend fun finish(): GeneratedMedia {
            val owner = checkNotNull(startedOwner)
            val value = GeneratedMedia(owner.callId, MediaKind.IMAGE, MediaPhase.READY, asset = MediaAsset("asset", "image/png", 1))
            operations.value = mapOf(operation to value)
            terminal.reconcileCompletion(owner, operation, value)
            return value
        }
        override suspend fun recover(operationId: String, onUpdate: suspend (GeneratedMedia) -> Unit) = operations.value[operationId]
        override suspend fun localPath(asset: MediaAsset) = "/image.png"
        override suspend fun refreshAvailability() = Unit
        override suspend fun check(kind: MediaKind, selection: MediaModelSelection, profile: LlmProfile): MediaConnectionStatus = error("unused")
        override suspend fun recoverMedia(mediaId: String): GeneratedMedia? = error("unused")
        override suspend fun recoverPending() = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override suspend fun read(asset: MediaAsset): ByteArray = error("unused")
        override suspend fun prepareForReset() = Unit
        override suspend fun resumeAfterReset() = Unit
        override suspend fun close() = Unit
    }
}
