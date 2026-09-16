package io.aequicor.magicpaper.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatWorkspacePresentationStoreTest {
    @Test fun panelConfigurationSurvivesChildSessionComponentsAndRemainsNotebookSpecific() {
        val store = ChatWorkspacePresentationStore()

        store.update("notebook-a") { it.copy(
            questionsExpanded = false,
            sourcesExpanded = false,
            sharedSourcesExpanded = false,
            questionSourcesExpanded = false,
            questionsWidth = 336f,
            sourcesWidth = 412f,
        ) }

        assertFalse(store.state("notebook-a").questionsExpanded)
        assertFalse(store.state("notebook-a").sourcesExpanded)
        assertFalse(store.state("notebook-a").sharedSourcesExpanded)
        assertFalse(store.state("notebook-a").questionSourcesExpanded)
        assertEquals(336f, store.state("notebook-a").questionsWidth)
        assertEquals(412f, store.state("notebook-a").sourcesWidth)
        assertTrue(store.state("notebook-b").questionsExpanded)
        assertTrue(store.state("notebook-b").sourcesExpanded)
        assertTrue(store.state("notebook-b").sharedSourcesExpanded)
        assertTrue(store.state("notebook-b").questionSourcesExpanded)
    }
}
