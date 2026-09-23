package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.logging.AppLog

/** Sidebar commands cross feature boundaries through their service APIs. */
internal class SidebarActions(private val chat: ChatService, contributions: List<SidebarContribution>) {
    private val sources = contributions.associateBy { it.sourceId }
    fun dispatch(sourceId: String, command: SidebarCommand) {
        val (name, id) = when (command) {
            SidebarCommand.Create -> "create" to null
            is SidebarCommand.CreateInProject -> "createInProject" to command.id
            is SidebarCommand.Select -> "select" to command.id
            is SidebarCommand.Archive -> "archive" to command.id
            is SidebarCommand.Stop -> "stop" to command.id
            is SidebarCommand.Restore -> "restore" to command.id
            is SidebarCommand.Delete -> "delete" to command.id
            SidebarCommand.DismissNotice -> "dismissNotice" to null
        }
        AppLog.info("sidebar", "action", listOfNotNull("source" to sourceId, "action" to name, id?.let { "entityId" to it }).toMap())
        if (sourceId != "chat") {
            requireNotNull(sources[sourceId]) { "Sidebar command has no registered owner" }.dispatch(command)
            return
        }
        when (command) {
            SidebarCommand.Create -> chat.newSession()
            is SidebarCommand.Select -> chat.selectSession(command.id)
            is SidebarCommand.Archive -> chat.archiveSession(command.id)
            is SidebarCommand.Stop -> error("Chat sessions cannot be stopped")
            is SidebarCommand.Restore -> chat.restoreSession(command.id)
            is SidebarCommand.Delete -> chat.deleteSession(command.id)
            SidebarCommand.DismissNotice -> chat.dismissNotice()
            is SidebarCommand.CreateInProject -> error("Chat does not own project sessions")
        }
    }
}
