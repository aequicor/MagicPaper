package io.aequicor.magicpaper.domain

/** Pure remapping: the caller supplies identity, so replay never generates a different history. */
fun ChatMessage.forChatFork(newId: String): ChatMessage {
    fun GeneratedMedia.remap() = interrupted().copy(id = "$newId:media:$id")
    return copy(id = newId, content = content.map { block -> when (block) {
        is TranscriptBlock.Markdown -> block.copy(id = "$newId:text:${block.id}")
        is TranscriptBlock.Media -> block.media.remap().let { TranscriptBlock.Media(it.id, it) }
    } }, researchActivity = researchActivity.map { it.copy(media = it.media?.remap()) })
}
