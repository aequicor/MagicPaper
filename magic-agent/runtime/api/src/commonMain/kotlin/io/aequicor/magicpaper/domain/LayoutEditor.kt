package io.aequicor.magicpaper.domain

/** Desktop adapter owns editor processes and file access; chat owns model orchestration. */
interface LayoutEditor {
    suspend fun open(project: CodingProject, conversationId: String): LayoutWorkspace
    suspend fun render(workspace: LayoutWorkspace, source: String): LayoutRender
    /** Compare against the source captured by open; never overwrite edits made in the editor. */
    suspend fun publish(workspace: LayoutWorkspace, source: String)
}

data class LayoutWorkspace(val projectId: String, val directory: String, val source: String, val catalog: String)
data class LayoutRender(val valid: Boolean, val diagnostics: String, val preview: Attachment? = null)

/** Only safe, actionable text crosses the native boundary into a chat response. */
class LayoutEditorException(message: String, cause: Throwable? = null) : Exception(message, cause)

object UnavailableLayoutEditor : LayoutEditor {
    override suspend fun open(project: CodingProject, conversationId: String): LayoutWorkspace =
        throw LayoutEditorException("Создание макетов доступно в desktop-версии MagicPaper с установленным Paper Editor.")
    override suspend fun render(workspace: LayoutWorkspace, source: String): LayoutRender = error("Editor unavailable")
    override suspend fun publish(workspace: LayoutWorkspace, source: String): Unit = error("Editor unavailable")
}

data class LayoutChatRequest(val project: CodingProject?, val conversationId: String, val requestId: String)
