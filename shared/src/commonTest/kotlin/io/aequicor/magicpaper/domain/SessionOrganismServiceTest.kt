package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.SessionOrganismStore
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionOrganismServiceTest {
    @Test fun deliveryAppearsAtBothEndsWithSessionOriginAndSurvivesReplay() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize()
        f.service.startChild = { _, _ -> }
        f.create("create")
        f.send("delivery", "session-create")
        val incoming = f.projects.messages(f.project.id, "session-create").single()
        val outgoing = f.projects.messages(f.project.id, f.root.id).single()
        assertEquals("context-delivery", incoming.id)
        assertEquals(CodingRole.USER, incoming.role)
        assertEquals(MessageOrigin.SESSION, incoming.origin)
        assertEquals("delivery", incoming.deliveryId)
        assertEquals(outgoing.deliveryId, incoming.deliveryId)
        assertEquals(incoming.text, outgoing.text)
        assertEquals(incoming.contextPacket, outgoing.contextPacket)
        assertEquals(MessageOrigin.TOOL, outgoing.origin)
        assertEquals("Контекст от сессии Зигота", incoming.route?.kind)
        assertEquals("root", incoming.route?.source?.sessionId)
        assertTrue(incoming.contextPacket!!.summarized)
        assertEquals(listOf("artifact:1"), incoming.contextPacket.attachments)
        val restarted = SessionOrganismService(SessionOrganismStore(f.storage) { 1_000 }, f.projects, f.settings)
        restarted.deliver(f.root.organismId!!)
        restarted.deliver(f.root.organismId!!)
        assertEquals(listOf(incoming), f.projects.messages(f.project.id, "session-create"))
        assertEquals(listOf(outgoing), f.projects.messages(f.project.id, "root"))
    }

    @Test fun crashBetweenRecipientProjectionAndSenderProjectionDoesNotDuplicateIncomingContext() = runTest {
        val backing = SessionOrganismTestFixture()
        var failSender = false
        val faultProjects = object : CodingProjectRepository by backing.projects {
            override suspend fun saveMessages(projectId: String, sessionId: String, messages: List<CodingMessage>) {
                if (failSender && sessionId == "root") { failSender = false; error("sender projection failed") }
                backing.projects.saveMessages(projectId, sessionId, messages)
            }
        }
        val f = SessionOrganismTestFixture(faultProjects, backing.storage); f.initialize()
        f.service.startChild = { _, _ -> }
        f.create("create")
        failSender = true
        assertFailsWith<IllegalStateException> { f.send("delivery", "session-create") }
        assertEquals(1, f.projects.messages(f.project.id, "session-create").size)
        assertTrue(f.projects.messages(f.project.id, "root").isEmpty())
        assertEquals(SessionDeliveryState.ACCEPTED, f.store.get(f.root.organismId!!).outbox.single().state)
        val restarted = SessionOrganismService(SessionOrganismStore(f.storage) { 1_000 }, faultProjects, f.settings)
        restarted.deliver(f.root.organismId!!)
        assertEquals(1, f.projects.messages(f.project.id, "session-create").size)
        assertEquals(1, f.projects.messages(f.project.id, "root").size)
        assertEquals(SessionDeliveryState.DELIVERED, restarted.store.get(f.root.organismId!!).outbox.single().state)
    }

    @Test fun noRuntimeOwnerLeavesCreationPendingAndRetryUsesSameChildAndBudget() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize()
        assertFailsWith<IllegalStateException> { f.create("create") }
        val accepted = f.store.get(f.root.organismId!!)
        assertEquals(SessionObservedState.PENDING, accepted.sessions.getValue("session-create").observed)
        var started: String? = null
        f.service.startChild = { child, _ -> started = child.id }
        f.create("create")
        assertEquals("session-create", started)
        assertEquals(accepted.sessions, f.store.get(accepted.id).sessions)
    }

    @Test fun codeModeCannotDelegateIntoSharedMutableCheckout() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.CODE)
        f.service.startChild = { _, _ -> error("must not launch") }
        assertFailsWith<IllegalArgumentException> { f.create("create") }
        assertEquals(2, f.store.get(f.root.organismId!!).sessions.size)
    }
}
