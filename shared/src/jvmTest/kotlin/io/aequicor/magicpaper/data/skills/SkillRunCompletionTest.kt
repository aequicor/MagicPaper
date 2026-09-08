package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.coding.DesktopCodingRuntime
import io.aequicor.magicpaper.data.coding.PiCodingRuntime
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.nio.file.*
import java.util.UUID
import kotlin.test.*

class SkillRunCompletionTest {
    private val unknown = SkillRunVerifier { SkillRunVerification() }
    private val passed = SkillRunVerifier { SkillRunVerification(ExperienceVerification.PASSED,
        ExperienceScenario.CHECKLIST, setOf(ExperienceFeature.MISSING_STEP)) }
    private fun workspace(block: suspend (Path, LocalSkillRepository) -> Unit) = runTest {
        val root = Files.createTempDirectory("run-experience-")
        try {
            LocalSkillRepository(root.resolve("repo"), SkillPackageHost("1.0.0", "desktop")).use { block(root, it) }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private fun journal(root: Path, repo: LocalSkillRepository, now: () -> Long = { 1_000L }) =
        LocalSkillExperience(root.resolve("experience"), repo, object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("No LLM access")
        }, { emptyList() }, now)
    private fun id() = UUID.randomUUID().toString()

    @Test fun desktopRuntimeUsesCheckpointIdentityAcrossCallsAndRecordsCancellationBeforeTransport() = workspace { root, repo ->
        journal(root, repo).use { j ->
            val runtime = DesktopCodingRuntime(PiCodingRuntime(root.resolve("pi").toFile()),
                CodexAppServerOpenAiSubscription(SkillPackageFormat.json, root.resolve("codex")),
                skillSnapshot = { throw CancellationException("private transport detail") }, experience = { j }, resultVerifier = passed)
            val project = CodingProject("project-private", "private name", root.toString(), 0)
            for (engine in CodingEngine.entries) {
                val request = CodingRunCheckpoint("request-$engine", "private prompt")
                val session = CodingSession("session-private", project.id, "private title", 0, engine = engine, pendingRun = request)
                repeat(2) {
                    assertFailsWith<CancellationException> { runtime.run(project, session, request.prompt, LlmProfile("p", "test")).collect() }
                }
                val row = j.search().single { it.id == skillRunIdentity(session.id, request.runId) }
                assertEquals(ExperienceResult.CANCELLATION, row.result)
                assertFalse(row.success)
            }
            assertEquals(2, j.search().size)
            assertFalse(Files.exists(root.resolve("pi")))
            assertFalse(Files.exists(root.resolve("codex")))
            assertFalse(Files.readString(root.resolve("experience/experience.json")).contains("private"))
        }
    }

    @Test fun legacyManualRowsRemainExplicitAndUnverifiedPositiveRowsAreRejectedOnOpen() = workspace { root, repo ->
        val file = root.resolve("experience/experience.json")
        Files.createDirectories(file.parent)
        val old = """{"schemaVersion":2,"outcomes":[{"id":"${id()}","time":1000,"scenario":"SUMMARY","success":true,"features":[]}]}"""
        Files.writeString(file, old)
        journal(root, repo).use { j ->
            assertEquals(ExperienceVerification.USER_CONFIRMED, j.search().single().verification)
        }
        Files.writeString(file, old.replace("\"success\":true", "\"success\":true,\"verification\":\"UNAVAILABLE\""))
        assertFails { journal(root, repo).close() }
        Files.writeString(file, old.replace("\"features\":[]", "\"features\":[],\"prompt\":\"private\""))
        assertFails { journal(root, repo).close() }
    }

    @Test fun checkpointIdentitySurvivesSerializationRecoveryAndSeparatesTurns() {
        val request = CodingRunCheckpoint("message-secret", "prompt-secret")
        val restored = SkillPackageFormat.json.decodeFromString<CodingRunCheckpoint>(SkillPackageFormat.json.encodeToString(request))
        val legacy = SkillPackageFormat.json.decodeFromString<CodingRunCheckpoint>("""{"messageId":"message-secret","prompt":"prompt-secret"}""")
        val identity = skillRunIdentity("session-secret", request.runId)
        assertEquals(identity, skillRunIdentity("session-secret", restored.copy(intent = ExecutionIntent.STOP).runId))
        assertEquals(identity, skillRunIdentity("session-secret", legacy.runId))
        assertNotEquals(identity, skillRunIdentity("other-session", request.runId))
        assertNotEquals(identity, skillRunIdentity("session-secret", "next-message"))
        assertEquals(identity, UUID.fromString(identity).toString())
        assertFalse(identity.contains("secret"))
    }

    @Test fun duplicateConcurrentCompletionAndReopenVerifyOnceAndRecordOnce() = workspace { root, repo ->
        val run = id()
        lateinit var ticket: SkillRunTicket
        var calls = 0
        val verifier = SkillRunVerifier { calls++; yield(); passed.verify(it) }
        journal(root, repo).use { j ->
            ticket = j.beginRun(run)
            assertEquals(ticket, j.beginRun(run))
            coroutineScope { List(12) { async { j.completeRun(ticket, ExperienceResult.UNKNOWN, verifier) } }.awaitAll() }
            assertEquals(1, calls)
            assertEquals(1, j.search().size)
            assertTrue(j.search().single().success)
        }
        journal(root, repo).use { j ->
            assertEquals(ticket, j.beginRun(run))
            j.completeRun(ticket, ExperienceResult.FAILURE, verifier)
            assertEquals(1, calls)
            assertEquals(ExperienceResult.SUCCESS, j.search().single().result)
        }
    }

    @Test fun allFourResultsAndFailedVerificationAreDistinct() = workspace { root, repo ->
        journal(root, repo).use { j ->
            val success = j.completeRun(j.beginRun(id()), ExperienceResult.UNKNOWN, passed)!!
            val failure = j.completeRun(j.beginRun(id()), ExperienceResult.FAILURE, passed)!!
            val cancellation = j.completeRun(j.beginRun(id()), ExperienceResult.CANCELLATION, passed)!!
            val unavailable = j.completeRun(j.beginRun(id()), ExperienceResult.UNKNOWN, unknown)!!
            assertEquals(ExperienceResult.entries.toSet(), setOf(success.result, failure.result, cancellation.result, unavailable.result))
            assertEquals(listOf(true, false, false, false), listOf(success, failure, cancellation, unavailable).map { it.success })
            val rejected = j.completeRun(j.beginRun(id()), ExperienceResult.UNKNOWN,
                SkillRunVerifier { SkillRunVerification(ExperienceVerification.FAILED, ExperienceScenario.SUMMARY, setOf(ExperienceFeature.TOO_LONG)) })!!
            assertEquals(ExperienceResult.FAILURE, rejected.result)
            assertEquals(setOf(ExperienceFeature.TOO_LONG), rejected.features)
            assertFails { j.completeRun(j.beginRun(id()), ExperienceResult.SUCCESS, passed) }
        }
    }

    @Test fun flowReplayFinishedAndModelClaimsNeverProducePositiveExperienceOrLeakText() = workspace { root, repo ->
        journal(root, repo).use { j ->
            val secret = "PRIVATE_CHAT_FILE_PROMPT_OUTPUT"
            val events = listOf(CodingEvent.SessionStarted(secret), CodingEvent.TextDelta(secret), CodingEvent.FinalText("SUCCESS $secret"),
                CodingEvent.ToolStarted(secret, secret, secret, true), CodingEvent.ToolFinished(secret, false, secret, secret),
                CodingEvent.Notice(secret), CodingEvent.AgentEnd, CodingEvent.Finished, CodingEvent.Finished)
            val flow = events.asFlow().withSkillExperience(id(), { j })
            assertEquals(events, flow.toList())
            flow.collect()
            val row = j.search().single()
            assertEquals(ExperienceResult.UNKNOWN, row.result)
            assertFalse(row.success)
            assertNull(row.scenario)
            assertTrue(row.features.isEmpty())
            val raw = Files.readString(root.resolve("experience/experience.json"))
            assertFalse(raw.contains(secret))
            for (key in listOf("prompt", "output", "chat", "path", "sessionId", "messages", "files")) assertFalse(raw.contains("\"$key\""))
            assertTrue(j.recurring().isEmpty())
        }
    }

    @Test fun failedCancelledIncompleteAndAbortedFlowsCannotUsePassedVerifier() = workspace { root, repo ->
        journal(root, repo).use { j ->
            var calls = 0
            val verifier = SkillRunVerifier { calls++; passed.verify(it) }
            flowOf(CodingEvent.Failed("private"), CodingEvent.Finished).withSkillExperience(id(), { j }, verifier).collect()
            assertFailsWith<CancellationException> {
                flow<CodingEvent> { throw CancellationException("private") }.withSkillExperience(id(), { j }, verifier).collect()
            }
            flowOf(CodingEvent.FinalText("done")).withSkillExperience(id(), { j }, verifier).collect()
            flowOf(CodingEvent.Finished).withSkillExperience(id(), { j }, verifier, cancelled = { true }).collect()
            assertEquals(listOf(ExperienceResult.FAILURE, ExperienceResult.CANCELLATION, ExperienceResult.UNKNOWN, ExperienceResult.CANCELLATION), j.search().map { it.result })
            assertEquals(0, calls)
        }
    }

    @Test fun deletionDuringIndependentVerificationAndReopenRejectLateCallbacks() = workspace { root, repo ->
        lateinit var ticket: SkillRunTicket
        journal(root, repo).use { j ->
            ticket = j.beginRun(id())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coroutineScope {
                val completing = async { j.completeRun(ticket, ExperienceResult.UNKNOWN, SkillRunVerifier {
                    entered.complete(Unit); release.await(); passed.verify(it)
                }) }
                entered.await()
                j.deleteAll()
                release.complete(Unit)
                assertNull(completing.await())
            }
            assertTrue(j.search().isEmpty())
            assertFalse(Files.readString(root.resolve("experience/experience.json")).contains(ticket.runId))
        }
        journal(root, repo).use { j ->
            assertNull(j.completeRun(ticket, ExperienceResult.UNKNOWN, passed))
            assertTrue(j.search().isEmpty())
            assertNotNull(j.completeRun(j.beginRun(id()), ExperienceResult.UNKNOWN, passed))
        }
    }

    @Test fun individualDeletionAndRetentionFencePendingRuns() = workspace { root, repo ->
        var time = 1_000L
        journal(root, repo) { time }.use { j ->
            val pending = j.beginRun(id())
            val completed = j.completeRun(j.beginRun(id()), ExperienceResult.UNKNOWN, passed)!!
            j.delete(setOf(completed.id))
            assertNull(j.completeRun(pending, ExperienceResult.UNKNOWN, passed))
            val expired = j.beginRun(id())
            time += 31 * 86_400_000L
            assertNull(j.completeRun(expired, ExperienceResult.UNKNOWN, passed))
            assertTrue(j.search().isEmpty())
        }
    }

    @Test fun unavailableThrowingAndTimedOutVerifiersFailClosed() = workspace { root, repo ->
        journal(root, repo).use { j ->
            for (verifier in listOf(unknown, SkillRunVerifier { error("private verifier output") },
                SkillRunVerifier { delay(6_000); passed.verify(it) })) {
                val row = j.completeRun(j.beginRun(id()), ExperienceResult.UNKNOWN, verifier)!!
                assertFalse(row.success)
                assertEquals(ExperienceResult.UNKNOWN, row.result)
            }
            assertFalse(Files.readString(root.resolve("experience/experience.json")).contains("private"))
        }
    }
}
