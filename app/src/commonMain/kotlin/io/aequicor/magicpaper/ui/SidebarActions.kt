package io.aequicor.magicpaper.ui

/** Sidebar commands cross feature boundaries through their service APIs. */
internal class SidebarActions(private val chat: ChatService, contributions: List<SidebarContribution>) {
    private val sources = contributions.associateBy { it.sourceId }
    fun dispatch(sourceId: String, command: SidebarCommand) {
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
