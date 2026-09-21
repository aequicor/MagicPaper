package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import io.aequicor.magicpaper.ui.screens.SessionRecencyTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppContributionsTest {
    private class Source(override val sourceId: String = "agent") : SidebarContribution {
        val commands = mutableListOf<SidebarCommand>()
        @Composable override fun snapshot(selection: SidebarSelection, recency: SessionRecencyTracker) = SidebarProjection()
        override fun dispatch(command: SidebarCommand) { commands += command }
    }

    @Test fun assemblyCopiesRegistrationsAndRejectsAmbiguousOwners() {
        val source = Source()
        val values = mutableListOf<SidebarContribution>(source)
        val assembled = AppContributions(sidebars = values)
        values.clear()
        assertEquals(listOf(source), assembled.sidebars)
        assertTrue(source.commands.isEmpty())
        assertFailsWith<IllegalArgumentException> { AppContributions(sidebars = listOf(source, Source())) }
        assertFailsWith<IllegalArgumentException> { AppContributions(sidebars = listOf(Source("chat"))) }
    }

    @Test fun sameSessionIdInAnotherSourceNeverDeletesTheChat() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = ModelSettingsFixture()
        val chat = fixture.prepareChat()
        try {
            val source = Source()
            val actions = SidebarActions(chat, listOf(source))
            actions.dispatch("agent", SidebarCommand.Delete("first"))
            actions.dispatch("agent", SidebarCommand.CreateInProject("project"))
            runCurrent()
            assertNotNull(fixture.chats.session("first"))
            assertEquals(listOf(SidebarCommand.Delete("first"), SidebarCommand.CreateInProject("project")), source.commands)
            assertFailsWith<IllegalArgumentException> { actions.dispatch("missing", SidebarCommand.Delete("first")) }
            assertNotNull(fixture.chats.session("first"))
        } finally { chat.close(); Dispatchers.resetMain() }
    }
}
