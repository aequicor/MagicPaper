package io.aequicor.magicpaper.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatWorkspacePresentationStoreTest {
    @Test fun panelConfigurationSurvivesChildSessionComponentsAndRemainsNotebookSpecific() {
        val store = ChatWorkspacePresentationStore()

        store.update("notebook-a") { it.copy(questionsExpanded = false, sourcesExpanded = false) }

        assertFalse(store.state("notebook-a").questionsExpanded)
        assertFalse(store.state("notebook-a").sourcesExpanded)
        assertTrue(store.state("notebook-b").questionsExpanded)
        assertTrue(store.state("notebook-b").sourcesExpanded)
    }
}
