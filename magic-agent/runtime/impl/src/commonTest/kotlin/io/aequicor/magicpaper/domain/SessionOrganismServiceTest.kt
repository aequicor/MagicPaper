package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.*

import io.aequicor.magicpaper.data.coding.DefaultSessionOrganismStore
import io.aequicor.magicpaper.data.coding.journalCodingProjects
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionOrganismServiceTest {
    @Test fun archiveVisibilityAndReadinessSurviveRepeatedProjectionWithoutLaunchingWork() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize()
        val id = f.root.organismId!!
        val root = f.store.get(id).sessions.getValue(f.root.id)
        f.store.observe(id, root.id, root.generation, SessionObservedState.COMPLETED)
        var starts = 0
        f.ports.startChild = { _, _ -> starts++ }
        val prior = f.projects.sessions(f.project.id).first { it.id == root.id }
        f.projects.dispatch(f.project.id, CodingMachine.Fact.ArchiveReadinessObserved(CodingMachine.SessionRef(root.id, prior.runtimeGeneration), prior, true, 100, 10_000))
        val session = f.projects.sessions(f.project.id).first { it.id == root.id }
        f.service.setArchiveVisibility(session, true)
        f.service.project(f.store.get(id))
        assertTrue(f.projects.sessions(f.project.id).first { it.id == root.id }.archived)
        f.service.setArchiveVisibility(session, false)
        val restored = f.projects.sessions(f.project.id).first { it.id == root.id }
        assertFalse(restored.archived)
        assertEquals(100L, restored.archiveReadySince)
        assertEquals(root.generation, restored.runtimeGeneration)
        assertEquals(0, starts)
    }

    @Test fun deliveryAppearsAtBothEndsWithSessionOriginAndSurvivesReplay() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize()
        f.ports.startChild = { _, _ -> }
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
        assertEquals(listOf("artifact:1"), incoming.contextPacket!!.attachments)
        val restarted = testOrganismService(DefaultSessionOrganismStore(f.storage, f.journal) { 1_000 }, f.projects, f.settings, ports = f.ports)
        restarted.deliver(f.root.organismId!!)
        restarted.deliver(f.root.organismId!!)
        assertEquals(listOf(incoming), f.projects.messages(f.project.id, "session-create"))
        assertEquals(listOf(outgoing), f.projects.messages(f.project.id, "root"))
    }

    @Test fun crashBetweenRecipientProjectionAndSenderProjectionDoesNotDuplicateIncomingContext() = runTest {
        val backing = SessionOrganismTestFixture()
        var failSender = false
        val faultProjects = object : CodingProjectOwner by backing.projects {
            override suspend fun dispatch(projectId: String, input: CodingMachine.Input): CodingMachine.Transition {
                if (failSender && input is CodingMachine.Fact.HistoryPublished && input.session.id == "root") {
                    failSender = false; error("sender projection failed")
                }
                return backing.projects.dispatch(projectId, input)
            }
        }
        val f = SessionOrganismTestFixture(faultProjects, backing.storage); f.initialize()
        f.ports.startChild = { _, _ -> }
        f.create("create")
        failSender = true
        assertFailsWith<IllegalStateException> { f.send("delivery", "session-create") }
        assertEquals(1, f.projects.messages(f.project.id, "session-create").size)
        assertTrue(f.projects.messages(f.project.id, "root").isEmpty())
        assertEquals(SessionDeliveryState.ACCEPTED, f.store.get(f.root.organismId!!).outbox.single().state)
        val restarted = testOrganismService(DefaultSessionOrganismStore(f.storage, f.journal) { 1_000 }, faultProjects, f.settings)
        restarted.deliver(f.root.organismId!!)
        assertEquals(1, f.projects.messages(f.project.id, "session-create").size)
        assertEquals(1, f.projects.messages(f.project.id, "root").size)
        assertEquals(SessionDeliveryState.DELIVERED, restarted.store.get(f.root.organismId!!).outbox.single().state)
    }

    @Test fun repeatedCommittedCreationNeverReplaysAnUnconfirmedNativeStart() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize()
        assertFailsWith<IllegalStateException> { f.create("create") }
        val accepted = f.store.get(f.root.organismId!!)
        assertEquals(SessionObservedState.PENDING, accepted.sessions.getValue("session-create").observed)
        var started: String? = null
        f.ports.startChild = { child, _ -> started = child.id }
        f.create("create")
        assertNull(started)
        assertEquals(accepted.sessions, f.store.get(accepted.id).sessions)
    }

    @Test fun codeModeCannotDelegateIntoSharedMutableCheckout() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.CODE)
        f.ports.startChild = { _, _ -> error("must not launch") }
        assertFailsWith<IllegalArgumentException> { f.create("create") }
        assertEquals(1, f.store.get(f.root.organismId!!).sessions.size, "Ordinary code has no planning immunity and must not create a rejected child")
    }

    @Test fun projectionPreservesAutoGeneratedNameFromRepository() = runTest {
        val f = SessionOrganismTestFixture(rootName = "Новая сессия"); f.initialize(CodingInteractionMode.CODE)
        // Simulate namedFromPrompt updating the repo name after organism creation.
        val repoSession = f.projects.sessions(f.project.id).single { it.id == f.root.id }
        assertFalse(repoSession.nameManuallySet)
        val autoName = "Найти ошибку в коде"
        f.projects.dispatch(f.project.id, CodingMachine.Intent.BeginRun(CodingMachine.SessionRef(repoSession.id, repoSession.runtimeGeneration),
            CodingRunCheckpoint("auto-request", autoName, responseId = "auto-response", responseTimelineId = "auto-timeline"), 100,
            localSummaryAllowed = true))
        // Re-project the organism (as prepareUserTurn/ensure would do on the next message).
        val organism = f.store.get(f.root.organismId!!)
        f.service.project(organism)
        val projected = f.projects.sessions(f.project.id).single { it.id == f.root.id }
        assertEquals(autoName, projected.name, "Auto-generated name must survive projection")
        assertFalse(projected.nameManuallySet)
    }
}
