package io.aequicor.magicpaper.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class GeneratedMediaTranscriptTest {
    @Test fun streamedAndReplayedMediaRetainsOneSlotBetweenParagraphs() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.TextDelta("До иллюстрации"))
        val pending = GeneratedMedia("image-call", MediaKind.IMAGE, caption = "Схема")
        recorder.apply(CodingEvent.ToolStarted("media.image", "", "call", media = pending))
        val first = recorder.timeline().transcriptContent()
        val key = first.filterIsInstance<TranscriptBlock.Media>().single().id
        recorder.apply(CodingEvent.ToolProgress("media.image", "call", media = pending.copy(phase = MediaPhase.DOWNLOADING)))
        val ready = pending.copy(phase = MediaPhase.READY, asset = MediaAsset("digest", "image/png", 9))
        repeat(2) { recorder.apply(CodingEvent.ToolFinished("media.image", false, "call", media = ready)) }
        recorder.apply(CodingEvent.TextDelta("После иллюстрации"))
        val blocks = recorder.message("answer", 1).steps.transcriptContent()
        assertEquals(listOf("До иллюстрации", "После иллюстрации"), blocks.filterIsInstance<TranscriptBlock.Markdown>().map { it.text })
        assertEquals(key, blocks[1].id)
        assertEquals(ready, (blocks[1] as TranscriptBlock.Media).media)
        assertEquals(3, blocks.size)
    }

    @Test fun historyReopensOrderedMediaWithoutEmbeddingItsBytes() {
        val asset = MediaAsset("digest", "video/mp4", 1500)
        val content = listOf(TranscriptBlock.Markdown("text", "Введение"),
            TranscriptBlock.Media("video", GeneratedMedia("video", MediaKind.VIDEO, MediaPhase.READY, asset = asset)))
        val session = ChatSession("chat", "Исследование", 1, 2,
            messages = listOf(ChatMessage("answer", ChatRole.AGENT, "Введение", 2, content = content)))
        val raw = Json.encodeToString(session)
        assertFalse("dataBase64" in raw)
        val restored = Json.decodeFromString<ChatSession>(raw)
        assertEquals(content, restored.messages.single().content)
        assertEquals(listOf(asset), restored.generatedMediaAssets())
        val legacy = Json.decodeFromString<ChatMessage>("""{"id":"old","role":"AGENT","text":"Ответ","createdAt":1}""")
        assertTrue(legacy.content.isEmpty())
    }

    @Test fun interruptedWorkIsNotPresentedAsStillGenerating() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.ToolStarted("media.video", "", "call", media = GeneratedMedia("video", MediaKind.VIDEO)))
        val response = recorder.message("answer", 2)
        assertEquals(MediaPhase.UNKNOWN, response.steps.single().media?.phase)
        val ready = GeneratedMedia("ready", MediaKind.IMAGE, MediaPhase.READY, asset = MediaAsset("asset", "image/png", 10))
        assertEquals(ready, ready.interrupted())
    }

    @Test fun portableSnapshotIncludesARecoveredAssetWithoutChangingItsReadingPosition() {
        val pending = GeneratedMedia("video", MediaKind.VIDEO, MediaPhase.UNKNOWN)
        val source = ChatSession("chat", "Чат", 1, 1, messages = listOf(ChatMessage("answer", ChatRole.AGENT, "Текст", 1,
            content = listOf(TranscriptBlock.Markdown("text", "Текст"), TranscriptBlock.Media("video", pending)))))
        val ready = pending.copy(phase = MediaPhase.READY, asset = MediaAsset("asset", "video/mp4", 100))
        val snapshot = source.withGeneratedMedia(mapOf("video" to ready))
        assertEquals(listOf(ready.asset), snapshot.generatedMediaAssets())
        assertEquals(listOf("text", "video"), snapshot.messages.single().content.map { it.id })
        assertEquals(MediaPhase.UNKNOWN, (source.messages.single().content.last() as TranscriptBlock.Media).media.phase)
    }
}
