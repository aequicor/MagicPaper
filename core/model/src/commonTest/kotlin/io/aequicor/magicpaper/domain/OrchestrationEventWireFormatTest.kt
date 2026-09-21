package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.OrchestrationEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wire form of every branch of [OrchestrationEvent], pinned byte for byte.
 *
 * The events are journaled: `CodingMachine.Intent.Orchestrate` carries one in its `event` field and the
 * journal replays it after a restart. None of its branches had a `@SerialName`, so the polymorphic
 * discriminator of each is the full name of the class *with its nesting* — `...planning.OrchestrationEvent.InputSubmitted`
 * — and any change to where a class lives or what it is called silently orphans every record written
 * under the old name. Each branch now names itself explicitly, with exactly the string it has always
 * been written under, so nothing stored has to be migrated.
 *
 * The strings below were taken from the encoder before the names were pinned, and the test passed
 * unchanged after. It is what makes the next change to this hierarchy — splitting it into intents and
 * facts, moving it — fail loudly instead of corrupting data. Encoding is compared against the journal's
 * own configuration, `Json { ignoreUnknownKeys = true; encodeDefaults = true }`.
 */
class OrchestrationEventWireFormatTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val plan = Plan("plan", "project", "Goal", runId = "run", parentSessionId = "parent", intent = ExecutionIntent.RUN)
    private val input = OrchestrationInput("input", "Request", 10)
    private val decision = UserTurnDecision(UserTurnIntent.entries.first(), "reply")
    private val pause = OrchestrationPause("plan", listOf("stage"))
    private val question = OrchestrationQuestion("question", "plan", "Text",
        listOf(PlanningQuestion("field", "Value", QuestionKind.TEXT)), "session")
    private val command = SessionCommand("command", SessionCommandKind.entries.first(), "session", "plan")
    private val event = MessageEvent("event", "source", "plan", "run", MessageEventKind.entries.first(), 1, "text")

    private val events: List<Pair<String, OrchestrationEvent>> = listOf(
        "Restore" to OrchestrationEvent.Restore,
        "InputSubmitted" to OrchestrationEvent.InputSubmitted(input),
        "InputEnqueued" to OrchestrationEvent.InputEnqueued(input),
        "UnsavedInputsRecovered" to OrchestrationEvent.UnsavedInputsRecovered(listOf(input)),
        "ScheduledRunsObserved" to OrchestrationEvent.ScheduledRunsObserved(listOf(plan)),
        "InputClaimRequested" to OrchestrationEvent.InputClaimRequested(listOf(plan)),
        "InputWithdrawn" to OrchestrationEvent.InputWithdrawn("input"),
        "InputRetried" to OrchestrationEvent.InputRetried("input", "clarification", true),
        "InputStatusRecorded" to OrchestrationEvent.InputStatusRecorded("input", OrchestrationInputStatus.DONE, "error"),
        "InputDecisionRecorded" to OrchestrationEvent.InputDecisionRecorded("input", decision),
        "LegacyRequestImported" to OrchestrationEvent.LegacyRequestImported(plan),
        "PlanSelected" to OrchestrationEvent.PlanSelected("plan"),
        "WorkPaused" to OrchestrationEvent.WorkPaused("plan", pause, true),
        "WorkPauseNeedsUser" to OrchestrationEvent.WorkPauseNeedsUser("plan"),
        "WorkPauseFinished" to OrchestrationEvent.WorkPauseFinished(plan, "plan"),
        "ProposalConfirmed" to OrchestrationEvent.ProposalConfirmed("plan", "proposal"),
        "PlanResumed" to OrchestrationEvent.PlanResumed(plan),
        "QuestionRegistered" to OrchestrationEvent.QuestionRegistered(question),
        "QuestionAnswered" to OrchestrationEvent.QuestionAnswered(plan, input, decision, 5),
        "QuestionResolved" to OrchestrationEvent.QuestionResolved("question", "pause", pause),
        "RequirementsQueued" to OrchestrationEvent.RequirementsQueued(input, pause),
        "QuestionsImported" to OrchestrationEvent.QuestionsImported(plan, listOf(CodingMessage("message", CodingRole.AGENT, "text", createdAt = 1))),
        "AnswerEventsRecorded" to OrchestrationEvent.AnswerEventsRecorded(listOf(event)),
        "StageNumbersRequested" to OrchestrationEvent.StageNumbersRequested("plan", listOf("one", "two")),
        "SessionCommandRegistered" to OrchestrationEvent.SessionCommandRegistered(command),
        "SessionCommandRejected" to OrchestrationEvent.SessionCommandRejected("command", "error"),
        "SessionCommandApplied" to OrchestrationEvent.SessionCommandApplied("command"),
        "SessionCommandDiscarded" to OrchestrationEvent.SessionCommandDiscarded("command"),
    )

    private val golden = mapOf(
        "Restore" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.Restore"}""",
        "InputSubmitted" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.InputSubmitted","input":{"id":"input","text":"Request","createdAt":10,"answers":[],"replyTo":null,"status":"QUEUED","decision":null,"error":"","resumeAfter":false,"scheduledRuleId":null,"sourcePlanId":null,"sourceRunId":null,"attempt":0,"sourceSessionId":null,"sourceText":null}}""",
        "InputEnqueued" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.InputEnqueued","input":{"id":"input","text":"Request","createdAt":10,"answers":[],"replyTo":null,"status":"QUEUED","decision":null,"error":"","resumeAfter":false,"scheduledRuleId":null,"sourcePlanId":null,"sourceRunId":null,"attempt":0,"sourceSessionId":null,"sourceText":null}}""",
        "UnsavedInputsRecovered" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.UnsavedInputsRecovered","inputs":[{"id":"input","text":"Request","createdAt":10,"answers":[],"replyTo":null,"status":"QUEUED","decision":null,"error":"","resumeAfter":false,"scheduledRuleId":null,"sourcePlanId":null,"sourceRunId":null,"attempt":0,"sourceSessionId":null,"sourceText":null}]}""",
        "ScheduledRunsObserved" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.ScheduledRunsObserved","plans":[{"id":"plan","projectId":"project","goal":"Goal","milestones":[],"plannerSelection":null,"engine":null,"searchProvider":"AUTO","wizardStep":null,"parentSessionId":"parent","sharedWorkspace":false,"worktreeEnabled":null,"confirmedRevision":null,"versions":[],"deliveries":[],"pendingRequest":"","pendingRecalculationNodeId":null,"requestId":"","coordination":[],"scheduledMessages":[],"messageEvents":[],"scheduleReceipts":{},"scheduleQuestionIds":[],"proposal":null,"replacesPlanId":null,"runHistory":[],"status":"DRAFT","sessionId":"","createdAt":0,"updatedAt":0,"schemaVersion":2,"revision":0,"tree":[],"dialogue":[],"priorities":{"quality":1,"speed":1,"economy":1,"safety":1},"intent":"RUN","stopping":false,"pendingSessionProjections":[],"phase":"IDLE","parallelism":2,"runId":"run","planningRulesSnapshot":null,"workspace":null,"finalAttempt":null,"finalAttemptHistory":[],"issue":null,"journal":[],"transportRetries":0,"acceptanceWaivers":[]}]}""",
        "InputClaimRequested" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.InputClaimRequested","plans":[{"id":"plan","projectId":"project","goal":"Goal","milestones":[],"plannerSelection":null,"engine":null,"searchProvider":"AUTO","wizardStep":null,"parentSessionId":"parent","sharedWorkspace":false,"worktreeEnabled":null,"confirmedRevision":null,"versions":[],"deliveries":[],"pendingRequest":"","pendingRecalculationNodeId":null,"requestId":"","coordination":[],"scheduledMessages":[],"messageEvents":[],"scheduleReceipts":{},"scheduleQuestionIds":[],"proposal":null,"replacesPlanId":null,"runHistory":[],"status":"DRAFT","sessionId":"","createdAt":0,"updatedAt":0,"schemaVersion":2,"revision":0,"tree":[],"dialogue":[],"priorities":{"quality":1,"speed":1,"economy":1,"safety":1},"intent":"RUN","stopping":false,"pendingSessionProjections":[],"phase":"IDLE","parallelism":2,"runId":"run","planningRulesSnapshot":null,"workspace":null,"finalAttempt":null,"finalAttemptHistory":[],"issue":null,"journal":[],"transportRetries":0,"acceptanceWaivers":[]}]}""",
        "InputWithdrawn" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.InputWithdrawn","id":"input"}""",
        "InputRetried" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.InputRetried","id":"input","clarification":"clarification","clearResumeAfter":true}""",
        "InputStatusRecorded" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.InputStatusRecorded","id":"input","status":"DONE","error":"error"}""",
        "InputDecisionRecorded" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.InputDecisionRecorded","id":"input","decision":{"intent":"DISCUSS","reply":"reply","replyTo":null,"completeAnswer":true,"command":"","stageId":"","proposalId":null,"requiresConfirmation":false,"questions":[],"schedules":[],"refinePlan":null,"pauseStageIds":[],"toolsApplied":false}}""",
        "LegacyRequestImported" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.LegacyRequestImported","plan":{"id":"plan","projectId":"project","goal":"Goal","milestones":[],"plannerSelection":null,"engine":null,"searchProvider":"AUTO","wizardStep":null,"parentSessionId":"parent","sharedWorkspace":false,"worktreeEnabled":null,"confirmedRevision":null,"versions":[],"deliveries":[],"pendingRequest":"","pendingRecalculationNodeId":null,"requestId":"","coordination":[],"scheduledMessages":[],"messageEvents":[],"scheduleReceipts":{},"scheduleQuestionIds":[],"proposal":null,"replacesPlanId":null,"runHistory":[],"status":"DRAFT","sessionId":"","createdAt":0,"updatedAt":0,"schemaVersion":2,"revision":0,"tree":[],"dialogue":[],"priorities":{"quality":1,"speed":1,"economy":1,"safety":1},"intent":"RUN","stopping":false,"pendingSessionProjections":[],"phase":"IDLE","parallelism":2,"runId":"run","planningRulesSnapshot":null,"workspace":null,"finalAttempt":null,"finalAttemptHistory":[],"issue":null,"journal":[],"transportRetries":0,"acceptanceWaivers":[]}}""",
        "PlanSelected" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.PlanSelected","id":"plan"}""",
        "WorkPaused" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.WorkPaused","id":"plan","pause":{"planId":"plan","stageIds":["stage"],"proposalId":null,"requiresUser":false},"mergeStages":true}""",
        "WorkPauseNeedsUser" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.WorkPauseNeedsUser","id":"plan"}""",
        "WorkPauseFinished" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.WorkPauseFinished","plan":{"id":"plan","projectId":"project","goal":"Goal","milestones":[],"plannerSelection":null,"engine":null,"searchProvider":"AUTO","wizardStep":null,"parentSessionId":"parent","sharedWorkspace":false,"worktreeEnabled":null,"confirmedRevision":null,"versions":[],"deliveries":[],"pendingRequest":"","pendingRecalculationNodeId":null,"requestId":"","coordination":[],"scheduledMessages":[],"messageEvents":[],"scheduleReceipts":{},"scheduleQuestionIds":[],"proposal":null,"replacesPlanId":null,"runHistory":[],"status":"DRAFT","sessionId":"","createdAt":0,"updatedAt":0,"schemaVersion":2,"revision":0,"tree":[],"dialogue":[],"priorities":{"quality":1,"speed":1,"economy":1,"safety":1},"intent":"RUN","stopping":false,"pendingSessionProjections":[],"phase":"IDLE","parallelism":2,"runId":"run","planningRulesSnapshot":null,"workspace":null,"finalAttempt":null,"finalAttemptHistory":[],"issue":null,"journal":[],"transportRetries":0,"acceptanceWaivers":[]},"id":"plan"}""",
        "ProposalConfirmed" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.ProposalConfirmed","planId":"plan","proposalId":"proposal"}""",
        "PlanResumed" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.PlanResumed","plan":{"id":"plan","projectId":"project","goal":"Goal","milestones":[],"plannerSelection":null,"engine":null,"searchProvider":"AUTO","wizardStep":null,"parentSessionId":"parent","sharedWorkspace":false,"worktreeEnabled":null,"confirmedRevision":null,"versions":[],"deliveries":[],"pendingRequest":"","pendingRecalculationNodeId":null,"requestId":"","coordination":[],"scheduledMessages":[],"messageEvents":[],"scheduleReceipts":{},"scheduleQuestionIds":[],"proposal":null,"replacesPlanId":null,"runHistory":[],"status":"DRAFT","sessionId":"","createdAt":0,"updatedAt":0,"schemaVersion":2,"revision":0,"tree":[],"dialogue":[],"priorities":{"quality":1,"speed":1,"economy":1,"safety":1},"intent":"RUN","stopping":false,"pendingSessionProjections":[],"phase":"IDLE","parallelism":2,"runId":"run","planningRulesSnapshot":null,"workspace":null,"finalAttempt":null,"finalAttemptHistory":[],"issue":null,"journal":[],"transportRetries":0,"acceptanceWaivers":[]}}""",
        "QuestionRegistered" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.QuestionRegistered","question":{"id":"question","planId":"plan","text":"Text","questions":[{"id":"field","title":"Value","kind":"TEXT","options":[],"allowCustomInput":true,"canSkip":true,"secret":false}],"sourceSessionId":"session","stageIds":[],"scopeLabel":"Для всего плана","status":"OPEN","partialAnswers":[],"partialMessages":{},"answerInputId":null,"forPlanning":false,"answeredAt":null,"answeredRunId":null,"refinementRequest":null,"forDiscussion":false,"pauseStageIds":null,"resolutionPending":false,"requirementContext":null}}""",
        "QuestionAnswered" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.QuestionAnswered","plan":{"id":"plan","projectId":"project","goal":"Goal","milestones":[],"plannerSelection":null,"engine":null,"searchProvider":"AUTO","wizardStep":null,"parentSessionId":"parent","sharedWorkspace":false,"worktreeEnabled":null,"confirmedRevision":null,"versions":[],"deliveries":[],"pendingRequest":"","pendingRecalculationNodeId":null,"requestId":"","coordination":[],"scheduledMessages":[],"messageEvents":[],"scheduleReceipts":{},"scheduleQuestionIds":[],"proposal":null,"replacesPlanId":null,"runHistory":[],"status":"DRAFT","sessionId":"","createdAt":0,"updatedAt":0,"schemaVersion":2,"revision":0,"tree":[],"dialogue":[],"priorities":{"quality":1,"speed":1,"economy":1,"safety":1},"intent":"RUN","stopping":false,"pendingSessionProjections":[],"phase":"IDLE","parallelism":2,"runId":"run","planningRulesSnapshot":null,"workspace":null,"finalAttempt":null,"finalAttemptHistory":[],"issue":null,"journal":[],"transportRetries":0,"acceptanceWaivers":[]},"input":{"id":"input","text":"Request","createdAt":10,"answers":[],"replyTo":null,"status":"QUEUED","decision":null,"error":"","resumeAfter":false,"scheduledRuleId":null,"sourcePlanId":null,"sourceRunId":null,"attempt":0,"sourceSessionId":null,"sourceText":null},"decision":{"intent":"DISCUSS","reply":"reply","replyTo":null,"completeAnswer":true,"command":"","stageId":"","proposalId":null,"requiresConfirmation":false,"questions":[],"schedules":[],"refinePlan":null,"pauseStageIds":[],"toolsApplied":false},"at":5}""",
        "QuestionResolved" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.QuestionResolved","id":"question","pauseId":"pause","pause":{"planId":"plan","stageIds":["stage"],"proposalId":null,"requiresUser":false}}""",
        "RequirementsQueued" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.RequirementsQueued","input":{"id":"input","text":"Request","createdAt":10,"answers":[],"replyTo":null,"status":"QUEUED","decision":null,"error":"","resumeAfter":false,"scheduledRuleId":null,"sourcePlanId":null,"sourceRunId":null,"attempt":0,"sourceSessionId":null,"sourceText":null},"pause":{"planId":"plan","stageIds":["stage"],"proposalId":null,"requiresUser":false}}""",
        "QuestionsImported" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.QuestionsImported","plan":{"id":"plan","projectId":"project","goal":"Goal","milestones":[],"plannerSelection":null,"engine":null,"searchProvider":"AUTO","wizardStep":null,"parentSessionId":"parent","sharedWorkspace":false,"worktreeEnabled":null,"confirmedRevision":null,"versions":[],"deliveries":[],"pendingRequest":"","pendingRecalculationNodeId":null,"requestId":"","coordination":[],"scheduledMessages":[],"messageEvents":[],"scheduleReceipts":{},"scheduleQuestionIds":[],"proposal":null,"replacesPlanId":null,"runHistory":[],"status":"DRAFT","sessionId":"","createdAt":0,"updatedAt":0,"schemaVersion":2,"revision":0,"tree":[],"dialogue":[],"priorities":{"quality":1,"speed":1,"economy":1,"safety":1},"intent":"RUN","stopping":false,"pendingSessionProjections":[],"phase":"IDLE","parallelism":2,"runId":"run","planningRulesSnapshot":null,"workspace":null,"finalAttempt":null,"finalAttemptHistory":[],"issue":null,"journal":[],"transportRetries":0,"acceptanceWaivers":[]},"messages":[{"id":"message","role":"AGENT","text":"text","activity":[],"steps":[],"failed":false,"createdAt":1,"attachments":[],"planning":null,"deliveryId":null,"pendingDelivery":false,"route":null,"inputStatus":null,"handoff":null,"scheduledRuleId":null,"timelineId":null,"systemContext":false,"systemNotice":false,"origin":"TOOL","contextPacket":null,"images":[],"inputAttachments":[]}]}""",
        "AnswerEventsRecorded" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.AnswerEventsRecorded","events":[{"id":"event","sourceKey":"source","planId":"plan","runId":"run","kind":"RESULT_RETURNED","at":1,"text":"text","taskId":null,"questionId":null,"attemptId":null,"turnIndex":null}]}""",
        "StageNumbersRequested" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.StageNumbersRequested","planId":"plan","stageIds":["one","two"]}""",
        "SessionCommandRegistered" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.SessionCommandRegistered","command":{"id":"command","kind":"CREATE","sessionId":"session","planId":"plan","stageId":"","name":"","applied":false,"error":""}}""",
        "SessionCommandRejected" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.SessionCommandRejected","id":"command","error":"error"}""",
        "SessionCommandApplied" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.SessionCommandApplied","id":"command"}""",
        "SessionCommandDiscarded" to """{"type":"io.aequicor.magicpaper.domain.planning.OrchestrationEvent.SessionCommandDiscarded","id":"command"}""",
    )

    @Test fun everyBranchEncodesToItsPinnedForm() {
        assertEquals(golden.keys, events.map { it.first }.toSet())
        for ((name, value) in events)
            assertEquals(golden.getValue(name), json.encodeToString(OrchestrationEvent.serializer(), value), name)
    }

    @Test fun everyPinnedFormDecodesBackToTheSameEvent() {
        for ((name, value) in events)
            assertEquals(value, json.decodeFromString(OrchestrationEvent.serializer(), golden.getValue(name)), name)
    }

    /** A branch added later must be pinned here too: a hierarchy of 28 has exactly these 28 names. */
    @Test fun theHierarchyHoldsExactlyThePinnedBranches() {
        val sealed = OrchestrationEvent.serializer().descriptor.getElementDescriptor(1)
        val names = (0 until sealed.elementsCount).map { sealed.getElementName(it) }.toSet()
        val pinned = golden.values.map { Regex("\"type\":\"([^\"]+)\"").find(it)!!.groupValues[1] }.toSet()
        assertEquals(pinned, names)
    }
}
