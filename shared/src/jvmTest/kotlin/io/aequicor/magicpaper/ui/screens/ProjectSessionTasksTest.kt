package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.CodingInteractionMode
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.domain.ImmunityAction
import io.aequicor.magicpaper.domain.ImmunityIntervention
import io.aequicor.magicpaper.domain.ImmunityInterventionState
import io.aequicor.magicpaper.domain.SessionKind
import io.aequicor.magicpaper.domain.SessionNode
import io.aequicor.magicpaper.domain.SessionOrganism
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectSessionTasksTest {
    @Test fun ordinaryConversationKeepsItsRowAfterAutomaticLifecycleAdoption() {
        val initial = session("root", organism = null, kind = null, name = "My conversation")
        val root = initial.copy(session = initial.session.copy(organismId = "organism", sessionKind = SessionKind.ZYGOTE))
        val immunity = session("immunity", kind = SessionKind.IMMUNITY)
        val before = CodingUi(sessions = listOf(initial)).projectSessionTasks("project").single()
        val after = CodingUi(sessions = listOf(root, immunity)).projectSessionTasks("project").single()
        assertEquals(before.key, after.key)
        assertEquals("My conversation", after.title)
        assertNull(after.organismId)
        assertEquals(listOf("root"), after.ids())
        val withDiagnostic = immunity.copy(failedRequest = true)
        assertEquals(setOf("root", "immunity"), CodingUi(sessions = listOf(root, withDiagnostic))
            .projectSessionTasks("project").single().ids().toSet())
        assertEquals(setOf("root", "child", "immunity"), CodingUi(sessions = listOf(root, immunity, session("child", parent = "root")))
            .projectSessionTasks("project").single().ids().toSet())
    }

    @Test fun zygoteAndImmunityShareATaskWithoutChangingTheirRuntimeParents() {
        val zygote = session("zygote", kind = SessionKind.ZYGOTE, createdAt = 10)
        val immunity = session("immunity", kind = SessionKind.IMMUNITY, createdAt = 99)
        val ui = CodingUi(sessions = listOf(immunity,
            session("late", parent = "zygote", createdAt = 30),
            session("grandchild", parent = "early", createdAt = 40),
            session("early", parent = "zygote", createdAt = 20), zygote))

        val task = ui.projectSessionTasks("project").single()

        assertEquals("task-organism", task.key)
        assertEquals("zygote", task.title)
        assertEquals("zygote", task.rootId)
        assertEquals("immunity", task.immunityId)
        assertEquals(listOf("zygote" to 0, "early" to 1, "grandchild" to 2, "late" to 1, "immunity" to 0),
            task.visibleRows(emptySet()).map { it.item.session.id to it.depth })
        assertEquals(listOf(2, 1, 0, 0, 0), task.visibleRows(emptySet()).map { it.childCount })
        assertNull(immunity.session.parentSessionId)
        assertNull(zygote.session.parentSessionId)
    }

    @Test fun identicalTitlesAndIdsInOtherProjectsDoNotMergeTasks() {
        val ui = CodingUi(sessions = listOf(
            session("root-a", organism = "a", kind = SessionKind.ZYGOTE, name = "Новая сессия", createdAt = 1),
            session("immune-a", organism = "a", kind = SessionKind.IMMUNITY),
            session("root-b", organism = "b", kind = SessionKind.ZYGOTE, name = "Новая сессия", createdAt = 2),
            session("immune-b", organism = "b", kind = SessionKind.IMMUNITY),
            session("root-a", project = "other", organism = "a", kind = SessionKind.ZYGOTE),
        ))

        val tasks = ui.projectSessionTasks("project")

        assertEquals(listOf("task-b", "task-a"), tasks.map { it.key })
        assertEquals(listOf(setOf("root-b", "immune-b"), setOf("root-a", "immune-a")),
            tasks.map { task -> task.sessions.map { it.session.id }.toSet() })
        assertTrue(tasks.flatMap { it.sessions }.all { it.session.projectId == "project" })
    }

    @Test fun collapseOfWorkTreeKeepsImmunityVisibleAndPreservesNestedDisclosure() {
        val task = CodingUi(sessions = listOf(
            session("zygote", kind = SessionKind.ZYGOTE), session("child", parent = "zygote"),
            session("grandchild", parent = "child"), session("immunity", kind = SessionKind.IMMUNITY),
        )).projectSessionTasks("project").single()

        assertEquals(listOf("zygote", "immunity"), task.ids(setOf("zygote", "child")))
        assertEquals(listOf("zygote", "child", "immunity"), task.ids(setOf("child")))
        assertEquals(listOf("zygote", "child", "grandchild", "immunity"), task.ids())
    }

    @Test fun metadataCanGroupLegacyProjectionsBeforeSessionIdentityReloads() {
        val organism = organism("organism", "zygote", "immunity", 1,
            SessionNode("child", SessionKind.SESSION, "Child", originParentId = "zygote"))
        val before = CodingUi(organisms = mapOf(organism.id to organism), sessions = listOf(
            session("immunity", organism = null), session("child", organism = null),
            session("zygote", organism = null, name = "Task"),
        ))
        val after = before.copy(sessions = before.sessions.map { item ->
            val node = organism.sessions.getValue(item.session.id)
            item.copy(session = item.session.copy(organismId = organism.id, sessionKind = node.kind,
                parentSessionId = node.originParentId))
        })

        val initial = before.projectSessionTasks("project").single()
        val reloaded = after.projectSessionTasks("project").single()
        assertEquals(initial.key, reloaded.key)
        assertEquals("Task", initial.title)
        assertEquals(listOf("zygote" to 0, "child" to 1, "immunity" to 0),
            initial.visibleRows(emptySet()).map { it.item.session.id to it.depth })
        assertEquals(initial.ids(), reloaded.ids())
    }

    @Test fun metadataLoadingDoesNotChangeAlreadyProjectedTaskIdentityOrOrder() {
        val sessions = listOf(
            session("immunity", kind = SessionKind.IMMUNITY, createdAt = 100),
            session("zygote", kind = SessionKind.ZYGOTE, createdAt = 1),
            session("new-root", organism = "new", kind = SessionKind.ZYGOTE, createdAt = 2),
        )
        val before = CodingUi(sessions = sessions)
        val loaded = before.copy(organisms = mapOf("organism" to organism("organism", "zygote", "immunity", 1)))

        assertEquals(listOf("task-new", "task-organism"), before.projectSessionTasks("project").map { it.key })
        assertEquals(before.projectSessionTasks("project").map { it.key }, loaded.projectSessionTasks("project").map { it.key })
        assertEquals(before.projectSessionTasks("project").last().ids(), loaded.projectSessionTasks("project").last().ids())
    }

    @Test fun pendingImmunityProposalKeepsTaskWaitingBeforeSessionIdentityReloads() {
        val proposal = ImmunityIntervention("proposal", "signal", "zygote", 0,
            setOf(ImmunityAction.PAUSE), listOf("Needs review"), setOf("zygote"), 1)
        val aggregate = organism("organism", "zygote", "immunity", 1).copy(interventions = listOf(proposal))
        val ui = CodingUi(organisms = mapOf(aggregate.id to aggregate), sessions = listOf(
            session("zygote", organism = null), session("immunity", organism = null),
        ))

        assertTrue(ui.sessionsOf("project").none { it.immunityProposalPending }, "Session identity has not reloaded yet")
        assertEquals(CodingSessionStatus.WAITING, ui.projectSessionTasks("project").single().status)
        val working = ui.copy(sessions = ui.sessions.map { it.copy(running = it.session.id == "zygote") })
        assertEquals(CodingSessionStatus.WAITING, working.projectSessionTasks("project").single().status)

        val withoutProposal = aggregate.copy(interventions = emptyList())
        assertEquals(CodingSessionStatus.IDLE,
            ui.copy(organisms = mapOf(aggregate.id to withoutProposal)).projectSessionTasks("project").single().status)
        assertEquals(CodingSessionStatus.WORKING,
            working.copy(organisms = mapOf(aggregate.id to withoutProposal)).projectSessionTasks("project").single().status)
        val completed = aggregate.copy(interventions = listOf(proposal.copy(state = ImmunityInterventionState.COMPLETED)))
        assertEquals(CodingSessionStatus.IDLE,
            ui.copy(organisms = mapOf(aggregate.id to completed)).projectSessionTasks("project").single().status)
        assertEquals(CodingSessionStatus.IDLE,
            ui.copy(organisms = mapOf(aggregate.id to aggregate.copy(deletedAt = 2))).projectSessionTasks("project").single().status)
    }

    @Test fun missingKindMetadataUsesParentlessWorkSessionWhenTimestampsMatch() {
        val ui = CodingUi(sessions = listOf(
            session("child", kind = null, parent = "zygote"),
            session("zygote", kind = null, name = "Task"),
            session("immunity", kind = SessionKind.IMMUNITY),
        ))

        val task = ui.projectSessionTasks("project").single()

        assertEquals("zygote", task.rootId)
        assertEquals("Task", task.title)
        assertEquals(listOf("zygote", "child", "immunity"), task.ids())
    }

    @Test fun archivedRootStillNamesAndOwnsItsTaskAndLegacyDescendants() {
        val ui = CodingUi(sessions = listOf(
            session("zygote", kind = SessionKind.ZYGOTE, name = "Saved task", archived = true),
            session("archived-parent", parent = "zygote", archived = true),
            session("orphan", organism = null, parent = "archived-parent"),
            session("immunity", kind = SessionKind.IMMUNITY),
        ))

        val task = ui.projectSessionTasks("project").single()

        assertEquals("Saved task", task.title)
        assertEquals("zygote", task.rootId)
        assertEquals(listOf("orphan", "immunity"), task.ids())
        assertEquals(listOf(0, 0), task.visibleRows(emptySet()).map { it.depth })
    }

    @Test fun missingRootUsesAggregateTitleAndTimestampInsteadOfImmunity() {
        val aggregate = organism("organism", "zygote", "immunity", 1).let {
            it.copy(sessions = it.sessions + ("zygote" to it.sessions.getValue("zygote").copy(name = "Saved task")))
        }
        val ui = CodingUi(organisms = mapOf(aggregate.id to aggregate), sessions = listOf(
            session("immunity", createdAt = 100),
            session("new-root", organism = "new", kind = SessionKind.ZYGOTE, createdAt = 2),
        ))

        val tasks = ui.projectSessionTasks("project")

        assertEquals(listOf("task-new", "task-organism"), tasks.map { it.key })
        assertEquals("Saved task", tasks.last().title)
        assertEquals(listOf("immunity"), tasks.last().ids())
        assertEquals("zygote", tasks.last().rootId)
    }

    @Test fun missingParentsAndCyclesKeepEveryVisibleSessionExactlyOnce() {
        val ui = CodingUi(sessions = listOf(
            session("zygote", kind = SessionKind.ZYGOTE), session("immunity", kind = SessionKind.IMMUNITY, parent = "zygote"),
            session("orphan", parent = "missing"), session("cycle-a", parent = "cycle-b"),
            session("cycle-b", parent = "cycle-a"), session("self", parent = "self"),
            session("archived", archived = true),
            session("legacy-a", organism = null, parent = "legacy-b"),
            session("legacy-b", organism = null, parent = "legacy-a"),
        ))

        val tasks = ui.projectSessionTasks("project")
        val rows = tasks.flatMap { it.visibleRows(emptySet()) }

        assertEquals(ui.sessions.filterNot { it.session.archived }.map { it.session.id }.toSet(),
            rows.map { it.item.session.id }.toSet())
        assertEquals(8, rows.size)
        assertEquals(0, rows.single { it.item.session.id == "immunity" }.depth)
        assertEquals(2, tasks.size)
        assertEquals(listOf("zygote", "orphan", "cycle-a", "cycle-b", "self", "immunity"),
            tasks.single { it.organismId != null }.ids())
    }

    @Test fun legacyRootsKeepTheirOrderAndChildrenUseCreationOrder() {
        val ui = CodingUi(sessions = listOf(
            session("first-root", organism = null, createdAt = 1),
            session("second-root", organism = null, createdAt = 1),
            session("new-root", organism = null, createdAt = 2),
            session("late-child", organism = null, parent = "first-root", createdAt = 4),
            session("early-child", organism = null, parent = "first-root", createdAt = 3),
        ))

        val tasks = ui.projectSessionTasks("project")

        assertEquals(listOf("session-new-root", "session-first-root", "session-second-root"), tasks.map { it.key })
        assertEquals(listOf("first-root", "early-child", "late-child"), tasks[1].ids())
        assertTrue(tasks.all { it.organismId == null })
    }

    @Test fun unrelatedMetadataAndNamesDoNotInventMembership() {
        val ui = CodingUi(organisms = mapOf("other" to organism("other", "zygote", "immunity", 1)
            .copy(projectId = "another-project")), sessions = listOf(
            session("zygote", organism = null, name = "Новая сессия"),
            session("immunity", organism = null, name = "Иммунитет", kind = SessionKind.IMMUNITY),
        ))

        val tasks = ui.projectSessionTasks("project")

        assertEquals(listOf("session-zygote", "session-immunity"), tasks.map { it.key })
        assertTrue(tasks.all { it.organismId == null })
        assertTrue(ui.projectSessionTasks("missing-project").isEmpty())
    }

    private fun ProjectSessionTask.ids(collapsed: Set<String> = emptySet()) =
        visibleRows(collapsed).map { it.item.session.id }

    private fun session(
        id: String,
        organism: String? = "organism",
        parent: String? = null,
        kind: SessionKind? = SessionKind.SESSION,
        createdAt: Long = 1,
        name: String = id,
        project: String = "project",
        archived: Boolean = false,
    ) = CodingSessionUi(CodingSession(id, project, name, createdAt, parentSessionId = parent,
        organismId = organism, sessionKind = kind, archived = archived, planningMode = kind == SessionKind.ZYGOTE))

    private fun organism(id: String, rootId: String, immunityId: String, createdAt: Long, vararg children: SessionNode) =
        SessionOrganism(id, "project", rootId, immunityId, createdAt, sessions =
            (listOf(SessionNode(rootId, SessionKind.ZYGOTE, rootId, mode = CodingInteractionMode.PLANNING),
                SessionNode(immunityId, SessionKind.IMMUNITY, immunityId)) + children).associateBy { it.id })
}
