package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.Id

class HistoryActionRejected(message: String) : IllegalStateException(message)

/** Clipboard text is built from the complete record, never a rendered/expanded fragment. */
fun ChatMessage.fullCopyText(): String = buildList {
    add(text)
    if (sources.isNotEmpty()) add(sources.joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet}" })
    attachments.forEach { add("Вложение: ${it.name}" + if (it.kind == AttachmentKind.TEXT) "\n${it.bytes.decodeToString()}" else "") }
}.filter { it.isNotBlank() }.joinToString("\n\n")

fun CodingMessage.fullCopyText(): String = buildList {
    if (steps.isEmpty()) { add(text); addAll(activity) }
    else {
        steps.forEach { step -> add(listOf(step.title, step.result).filter { it.isNotBlank() }.joinToString("\n")) }
        if (text.isNotBlank() && steps.none { it.title == text || it.result == text }) add(text)
    }
    attachments.forEach { add("Вложение: ${it.name}") }
    inputAttachments.filter { it.kind == AttachmentKind.TEXT }.forEach { add("${it.name}\n${it.bytes.decodeToString()}") }
}.filter { it.isNotBlank() }.joinToString("\n\n")

fun <T> List<T>.through(messageId: String?, id: (T) -> String): List<T> {
    if (messageId == null) return this
    val index = indexOfFirst { id(it) == messageId }
    require(index >= 0) { "Сообщение больше не существует" }
    return take(index + 1)
}

/** A fork copies dialogue, never execution authority, pending deliveries or workspace ownership. */
fun CodingSession.fork(id: String = Id.new()): CodingSession = CodingSession(
    id = id, projectId = projectId, name = "$name — форк", createdAt = Id.now(),
    llmProfileId = llmProfileId, modelSelection = modelSelection, engine = engine,
    planningMode = planningMode, researchMode = researchMode, needsHistorySeed = true,
    role = if (planningMode) CodingSessionRole.ORCHESTRATOR else CodingSessionRole.CHAT,
    searchProvider = searchProvider, featureFlags = featureFlags, worktreeEnabled = worktreeEnabled,
    nameManuallySet = true, mediaTools = mediaTools,
)

fun CodingMessage.forFork(forkSessionId: String): CodingMessage {
    val newId = Id.new()
    val newTimeline = timelineId?.let { Id.new() }
    fun CodingImageReference.remap() = copy(sessionId = forkSessionId, ownerMessageId = newId,
        timelineId = newTimeline ?: timelineId)
    return copy(id = newId, timelineId = newTimeline, planning = null, deliveryId = null, pendingDelivery = false,
        route = null, inputStatus = null, handoff = null, scheduledRuleId = null, contextPacket = null,
        images = images.map { it.remap() }, steps = steps.map { step -> step.copy(images = step.images.map { it.remap() },
            media = step.media?.let { it.interrupted().copy(id = "$newId:media:${it.id}") }) })
}

fun ChatMessage.forFork(): ChatMessage = forChatFork(Id.new())
