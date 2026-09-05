package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanRunnerTest {

    /** Фейковый рантайм: сценарий «что отвечать» на каждый промпт. */
    private class FakeRuntime(
        private val replies: Map<String, String>,
        private val default: String = "готово",
    ) : CodingRuntime {
        val prompts = mutableListOf<String>()
        override val supported = true
        override val rootPath = "/tmp/fake"

        override suspend fun status(): RuntimeStatus = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady(): Flow<RuntimeStatus> = flowOf(RuntimeStatus(RuntimePhase.READY))

        override fun run(
            project: CodingProject,
            session: CodingSession,
            prompt: String,
            profile: LlmProfile?,
            attachments: List<Attachment>,
        ): Flow<CodingEvent> {
            prompts += prompt
            val reply = replies.entries.firstOrNull { prompt.contains(it.key) }?.value ?: default
            return flowOf(
                CodingEvent.TextDelta(reply),
                CodingEvent.Finished,
            )
        }

        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun uninstall() = Unit
    }

    private class AlwaysPass : MilestoneVerifier {
        override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict =
            Verdict(passed = true, note = "ок")
    }

    /** Проваливает проверку указанного по заголовку мэилстоуна. */
    private class FailOn(private val title: String) : MilestoneVerifier {
        override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict =
            if (milestone.title == title) {
                Verdict(passed = false, note = "критерий не достигнут")
            } else {
                Verdict(passed = true, note = "ок")
            }
    }

    private val project = CodingProject(id = "proj", name = "тест", path = "/tmp/proj", createdAt = 1L)
    private val session = CodingSession(id = "sess", projectId = "proj", name = "план", createdAt = 1L)
    private val agent = LlmProfile(id = "agent", name = "Агент", baseUrl = "http://x/v1", modelId = "m")

    private fun plan(milestones: List<Milestone>) = Plan(
        id = "plan",
        projectId = "proj",
        goal = "цель",
        milestones = milestones,
        sessionId = "sess",
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun runsAllMilestonesAndReports() = runTest {
        val runtime = FakeRuntime(mapOf("шаг один" to "отчёт один", "шаг два" to "отчёт два"))
        val runner = PlanRunner(runtime, AlwaysPass())
        val updates = mutableListOf<Plan>()
        val final = runner.run(
            plan(
                listOf(
                    Milestone(id = "m1", title = "шаг один", agentProfileId = "agent"),
                    Milestone(id = "m2", title = "шаг два", agentProfileId = "agent"),
                )
            ),
            project,
            session,
            listOf(agent),
            judge = null,
            onUpdate = { updates += it },
        )

        assertEquals(PlanStatus.DONE, final.status)
        assertTrue(final.milestones.all { it.status == MilestoneStatus.DONE })
        assertEquals("отчёт один", final.milestones.first { it.id == "m1" }.report)
        // Промпт каждого шага содержит общую цель и критерий шага.
        assertTrue(runtime.prompts.all { it.contains("цель") })
        // Лента обновлений: статусы шли живьём (ACTIVE вехи видны в истории).
        assertTrue(updates.any { p -> p.milestones.any { it.status == MilestoneStatus.ACTIVE } })
    }

    @Test
    fun stopsOnFailedVerification() = runTest {
        val runtime = FakeRuntime(emptyMap())
        val runner = PlanRunner(runtime, FailOn("шаг два"))
        val final = runner.run(
            plan(
                listOf(
                    Milestone(id = "m1", title = "шаг один", agentProfileId = "agent"),
                    Milestone(id = "m2", title = "шаг два", agentProfileId = "agent"),
                    Milestone(id = "m3", title = "шаг три", agentProfileId = "agent"),
                )
            ),
            project,
            session,
            listOf(agent),
            judge = null,
            onUpdate = {},
        )

        assertEquals(PlanStatus.FAILED, final.status)
        assertEquals(MilestoneStatus.DONE, final.milestones[0].status)
        assertEquals(MilestoneStatus.FAILED, final.milestones[1].status)
        assertTrue(final.milestones[1].checkNote.isNotBlank())
        // Третий шаг не запускался.
        assertEquals(MilestoneStatus.PENDING, final.milestones[2].status)
    }

    @Test
    fun resumesFromFailedMilestone() = runTest {
        val runtime = FakeRuntime(emptyMap())
        val runner = PlanRunner(runtime, AlwaysPass())
        val initial = plan(
            listOf(
                Milestone(id = "m1", title = "шаг один", status = MilestoneStatus.DONE, agentProfileId = "agent"),
                Milestone(id = "m2", title = "шаг два", status = MilestoneStatus.FAILED, agentProfileId = "agent"),
            )
        )
        val final = runner.run(initial, project, session, listOf(agent), judge = null, onUpdate = {})
        assertEquals(PlanStatus.DONE, final.status)
        // Выполнялся только проваленный шаг (он следующий в очереди).
        assertEquals(1, runtime.prompts.size)
    }

    @Test
    fun abortStopsPlanBetweenMilestones() = runTest {
        val runtime = FakeRuntime(emptyMap())
        val runner = PlanRunner(runtime, AlwaysPass())
        var aborted = false
        val final = runner.run(
            plan(
                listOf(
                    Milestone(id = "m1", title = "шаг один", agentProfileId = "agent"),
                    Milestone(id = "m2", title = "шаг два", agentProfileId = "agent"),
                )
            ),
            project,
            session,
            listOf(agent),
            judge = null,
            onUpdate = { p -> if (p.milestones.any { it.status == MilestoneStatus.DONE }) aborted = true },
            isAborted = { aborted },
        )
        assertEquals(PlanStatus.STOPPED, final.status)
        assertEquals(MilestoneStatus.PENDING, final.milestones[1].status)
    }
}
