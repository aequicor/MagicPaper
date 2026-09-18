package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/** Ordered reading content, independent of the disclosure state of tool diagnostics. */
@Serializable
sealed interface TranscriptBlock {
    val id: String

    @Serializable
    data class Markdown(override val id: String, val text: String) : TranscriptBlock

    @Serializable
    data class Media(override val id: String, val media: GeneratedMedia) : TranscriptBlock
}

/** Media keeps its accepted call position while progress and terminal snapshots replace its value. */
fun List<CodingStep>.transcriptContent(): List<TranscriptBlock> = mapIndexedNotNull { index, step ->
    step.media?.let { TranscriptBlock.Media(it.id, it) }
        ?: step.takeIf { it.kind == CodingStepKind.ANSWER && it.title.isNotBlank() }?.let {
            TranscriptBlock.Markdown(it.id.ifBlank { "legacy-answer:$index" }, it.title)
        }
}

fun mergeTranscriptContent(previous: List<TranscriptBlock>, incoming: List<TranscriptBlock>): List<TranscriptBlock> {
    val result = previous.toMutableList()
    incoming.forEach { block ->
        val index = result.indexOfFirst { it.id == block.id }
        if (index < 0) result += block else {
            val previousMedia = result[index] as? TranscriptBlock.Media
            result[index] = if (previousMedia != null && block is TranscriptBlock.Media)
                block.copy(media = checkNotNull(previousMedia.media.mergeMedia(block.media))) else block
        }
    }
    return result
}

/** Reopening a saved operation does not imply that a remote request is still running. */
fun GeneratedMedia.interrupted(): GeneratedMedia = if (phase in setOf(MediaPhase.PENDING, MediaPhase.GENERATING, MediaPhase.DOWNLOADING))
    copy(phase = MediaPhase.UNKNOWN, message = "Создание прервано. Проверьте результат перед повтором.") else this

/** A replayed start/progress notification cannot remove a committed result. */
internal fun GeneratedMedia?.mergeMedia(incoming: GeneratedMedia?): GeneratedMedia? =
    if (this?.phase == MediaPhase.READY && (incoming == null || incoming.id == id)) this else incoming ?: this

fun List<TranscriptBlock>.interruptedMedia(): List<TranscriptBlock> = map {
    if (it is TranscriptBlock.Media) it.copy(media = it.media.interrupted()) else it
}

fun ChatSession.generatedMediaAssets(): List<MediaAsset> = buildList {
    messages.forEach { message ->
        addAll(message.content.mapNotNull { (it as? TranscriptBlock.Media)?.media?.asset })
        addAll(message.researchActivity.mapNotNull { it.media?.asset })
    }
    addAll(pendingContent.mapNotNull { (it as? TranscriptBlock.Media)?.media?.asset })
    addAll(pendingActivity.mapNotNull { it.media?.asset })
}.distinctBy { it.id }

/** Freeze the latest durable operation outcome before exporting or forking a reading snapshot. */
fun ChatSession.withGeneratedMedia(operations: Map<String, GeneratedMedia>): ChatSession = copy(
    messages = messages.map { message -> message.copy(content = message.content.withGeneratedMedia(operations),
        researchActivity = message.researchActivity.withGeneratedMediaSteps(operations)) },
    pendingContent = pendingContent.withGeneratedMedia(operations),
    pendingActivity = pendingActivity.withGeneratedMediaSteps(operations),
)

fun CodingMessage.withGeneratedMedia(operations: Map<String, GeneratedMedia>): CodingMessage =
    copy(steps = steps.withGeneratedMediaSteps(operations))

private fun GeneratedMedia.fromOperations(operations: Map<String, GeneratedMedia>): GeneratedMedia =
    checkNotNull(mergeMedia(operations[id]?.takeIf { it.kind == kind }))

private fun List<TranscriptBlock>.withGeneratedMedia(operations: Map<String, GeneratedMedia>): List<TranscriptBlock> = map { block ->
    if (block is TranscriptBlock.Media) block.copy(media = block.media.fromOperations(operations)) else block
}

private fun List<CodingStep>.withGeneratedMediaSteps(operations: Map<String, GeneratedMedia>): List<CodingStep> = map { step ->
    step.copy(media = step.media?.fromOperations(operations))
}
