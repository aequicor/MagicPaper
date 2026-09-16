package io.aequicor.magicpaper.ui

/** Sidebar commands cross feature boundaries through their service APIs. */
class SidebarActions(private val chat: ChatService, private val coding: CodingService) {
    fun newSession() = chat.newSession()
    fun addCodingProject() = coding.addCodingProject()
    fun requestCodingSessionInProject(id: String) = coding.requestCodingSessionInProject(id)
    fun selectUnifiedSession(id: String, isCoding: Boolean) {
        if (isCoding) coding.selectCodingSession(id) else chat.selectSession(id)
    }
    fun archiveCodingSession(id: String) = coding.archiveCodingSession(id)
    fun restoreSession(id: String, isCoding: Boolean) {
        if (isCoding) coding.restoreCodingSession(id) else chat.restoreSession(id)
    }
    fun archiveChatSession(id: String) = chat.archiveSession(id)
    fun deleteCodingSession(id: String) = coding.deleteCodingSession(id)
    fun deleteSession(id: String) = chat.deleteSession(id)
}
