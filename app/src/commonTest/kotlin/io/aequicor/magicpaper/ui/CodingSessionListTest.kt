package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.CodingSession
import kotlin.test.Test
import kotlin.test.assertEquals

class CodingSessionListTest {
    private fun ui(id: String, name: String = id) = CodingSessionUi(CodingSession(id, "p", name, createdAt = 1))

    @Test
    fun publishedSessionGoesFirst() {
        val list = listOf(ui("a"), ui("b")).withSessionFirst(ui("c"))
        assertEquals(listOf("c", "a", "b"), list.map { it.session.id })
    }

    @Test
    fun sessionAlreadyProjectedByTheJournalIsNotDuplicated() {
        // The journal observer can publish a created session before its creator does.
        val list = listOf(ui("a"), ui("b", "projected")).withSessionFirst(ui("b", "created"))
        assertEquals(listOf("b", "a"), list.map { it.session.id })
        assertEquals("created", list.first().session.name)
    }
}
