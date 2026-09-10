# Session runtime and questionnaire recovery

This document describes the implemented boundaries. The organism aggregate and its
command protocol are documented separately; a successful model reply is never a
substitute for an application operation receipt or a runtime observation.

## Runtime ownership

`SessionTreeRuntime` owns a coroutine scope for each live ordinary session. A
`SupervisorJob` holds its immediate child jobs. Returning from the parent's model
body closes admission and waits for those children; cancellation cancels and joins
the whole coroutine subtree. A native `Failed` event also cancels waiting children
before joining them, even when the native event stream then ends normally. Stored
session history outlives this scope.

The selected failure policy is enforced in code. `ISOLATE` lets independent sibling
jobs continue. A child observed as failed under `CANCEL_SIBLINGS` causes the sibling
subtrees to receive cancellation, including native abort signals and questionnaire
revocation. Declared task dependencies are joined before the dependent native run;
a failed or stopped dependency does not count as successful completion.

Registration, session hydration, native startup, question cleanup and result
persistence are all inside cleanup boundaries. A failure before the native run
records a failed attempt rather than leaving the child pending. Cleanup failures
cannot skip cancellation of another child, or leave a live handle permanently
registered. Runtime result IDs contain the captured execution generation. The
aggregate checks that generation before a result is saved or projected into the
conversation, so a late old result cannot be attributed to a replacement run.

`DesktopCodingRuntime` additionally holds an in-process lease containing the
session ID, generation and coroutine Job. Abort revokes this Job even during
preflight, before a process exists. Another run cannot replace a cancelled run
until its cleanup releases the lease. An older generation cannot acquire that
lease after a newer generation has been observed.

## Requested stop and observed stop

`Plan.intent = STOP` expresses the request. `Plan.stopping = true` means the request
has not yet been confirmed. The scheduler retains this flag through coroutine and
native cleanup; it writes `STOPPED` only after reconciling the attempt, merge and
final-delivery runtime identities. A failed reconciliation leaves an `UNCERTAIN`
issue and prevents start/retry. Persisted pending stops are reconciled at startup.
Cancelled jobs remain unavailable for replacement until `isCompleted`, rather
than merely `!isActive`.

The generic tree also reconciles native runtime ownership before recording a
terminal observation. Reconciliation or question-cleanup failure produces
`UNKNOWN`, and archive finalization requires every affected node to be settled.
Both the scheduler and the tree cancel all owned jobs even if a question store or
native abort signal fails. Tree shutdown closes admission and joins owned work.
An explicit stop retry repeats questionnaire cleanup even after the live job has
ended; continued cleanup failure keeps the session unconfirmed.

A failed or cancelled zygote also persists a stop for its declared descendants
and cancels independently owned legacy stage handles. The owning plan controller
must confirm cleanup before the root settles. A callback running inside that
controller requests stop without joining its own ancestor; confirmation remains
pending and the root stays `UNKNOWN`. An already completed idle plan is preserved.
The independent immunity root is outside this descendant cancellation.

Reconciliation confirms termination, not the success or rollback of external
side effects. An interrupted potentially mutating tool retains an `UNKNOWN` tool
phase and its saved pending-effect checkpoint. Stopping or archiving a session does
not undo an already completed command, Git operation or API request.

## Generic coding child workspaces

Ordinary `CODE` children use the same `PlanningWorkspace` implementation as plan
stages. The aggregate persists a `SessionCodingWorkspace` intention, stable run and
attempt IDs, and the captured runtime generation before preparing a Git worktree.
The child executes with the original project identity and its own workspace path.
The user's source checkout, HEAD and index are not delivery targets of this path.

Direct ordinary `CODE` roots keep the selected project path and acquire the same
writer gate before native startup. A second root cannot write that project while
another root is running, stopping or has an unresolved runtime outcome. The lease
outlives cancellation until native cleanup and reconciliation finish. An uncertain
stop or failed release retains the lease for explicit reconciliation; shutdown
includes these retained owners. Planner stages and auxiliary runs retain the
planner's existing workspace lifecycle.

Each child holds a writer lease for its actual worktree. A nested child snapshots
the active parent's worktree through the planner's private index; its own writer
uses another worktree and lease. The host compares the saved task source
fingerprint and the source fingerprint before and after preparation. A changed
source, unavailable verification, busy workspace or non-Git source prevents native
execution. Non-Git projects are never initialized automatically.

After the native run and all children end, native reconciliation must succeed
before the app captures the milestone commit or releases the writer. Capture
persists `CAPTURING` before touching Git and `CAPTURED` with SHA and content
fingerprint afterward. The result stores its workspace path, baseline commit,
source fingerprint, final fingerprint and commit SHA. Review checks the actual
saved worktree fingerprint. A changed worktree cannot be accepted using old
evidence. Failure after a Git effect remains `UNKNOWN`; the same runtime
generation cannot prepare a second workspace or replay the effect blindly.

A failed native reconciliation retains the writer lease for explicit subsequent
stop/reconciliation. A successful capture does not merge another child's result
or apply it to the user's checkout.

The separate generic integration operation combines accepted child results in a
new managed integration worktree using the same planner Git implementation. Its
immutable request identifies the actor generation, exact result IDs, source path
and fingerprint, and required check argument arrays. The helper saves intent and
merge progress before continuing. It runs the checks through the existing research
check sandbox and records actual exit codes and output as application evidence for
`AcceptanceGate`. A failed or unavailable check cannot produce a verified result.
Source and integration fingerprints must remain valid. The final combined commit
is published as `VERIFIED` only after check processes and writer leases have been
reconciled. A running parent's source lease can be borrowed, but the integration
always owns a separate writer lease. The parent receives a combined worktree and
SHA; this operation does not apply files to the user's checkout. Conflicts and
unknown effects remain explicit saved outcomes and are never replayed implicitly.

Git, workspace descriptor files and the organism aggregate remain separate
durability domains. Source fingerprint checks detect observed concurrent edits;
they do not lock out another application or the user's editor. Older generic
results without a captured source path cannot pass integration preflight.

## Durable local questionnaires

`RuntimeQuestionnaires` stores records independently from its in-memory waiting
coroutines. Records contain the stable request ID, project, author session,
runtime generation, run ID, questions, confirmed answers and state:

- `OPEN`: the request was published and has not received a confirmed answer.
- `ANSWERED`: the confirmed answers were saved before completing the waiter.
- `DELIVERED`: the native broker completed its reply write. This is not proof that
  the engine processed the reply or successfully completed the task.
- `CANCELLED`: the author scope or exact request was revoked.
- `INTERRUPTED`: a saved open request was loaded without its original transport.

The author session owns the runtime questionnaire in the UI. A child question does
not make the parent or an unrelated sibling wait. Merge/delivery runtime aliases
are projected to their actual source session. The notification and source-session
views use the same ID; there is no second independently answerable questionnaire.

Reattachment checks the exact ID, project, session, source, generation, run ID,
kind and questions. Reusing an ID with different arguments or a different owner is
rejected. A saved non-secret answer may be replayed to that exact call. Secret
answers marked by the native question schema are omitted from persistence and
must be confirmed again after reattachment; preselected values and drafts never
complete a waiter. Responding to an inactive or cancelled request fails.

The registry bounds active pending questionnaires. Publication and confirmed
answers are saved before they become observable or complete their waiter. A
failed save leaves the previous request actionable. If the store throws after an
atomic replacement, the registry reads back and verifies the exact snapshot. A
conflicting or unreadable outcome blocks further writes instead of overwriting a
possibly committed answer from stale memory. The HTTP bridge joins pending
question cancellation before its close call returns, and rejects reused JSON-RPC
IDs with different arguments.

The application tool registry uses the shared application key-value store. Native
Pi questionnaire records live beside the engine installation, in
`coding-questionnaires`, so uninstalling the engine does not remove them. Native
Codex clients share their questionnaire registry and use the Codex application data
directory. Independent clients filter the shared request list by author session.

## Transaction and recovery boundaries

Questionnaire persistence is one serialized snapshot replacement. The registry
mutex serializes commands in one application instance; the desktop adapter syncs
the file before replacement. Its saved state is not an ACID transaction with the
runtime connection, tool receipt store, organism aggregate, legacy session/message
projections, Git, filesystem changes or external APIs.

The organism aggregate is authoritative for generation-fenced task results.
Deterministic message IDs and the service projection mutex prevent duplicate
history projections and lost updates between tree results and delivered context.
A result remains unaccepted until its responsible recipient accepts it through
application policy. A model response alone does not accept a milestone.

There is no automatic conversion of a lost native connection into a new awaiting
call. Open native questionnaires survive as interrupted records, but the exact
original call must reconnect before it can receive an answer. A replacement
runtime has a new generation and cannot accidentally receive an older answer. The
current UI presents active questionnaires; it does not provide a general browser
for all interrupted native questionnaire records. Full native transport
reattachment is still a limitation.

## Compatibility and limits

Existing serialized sessions and plans decode with generation `0`, no rules
snapshot and `stopping = false`. The organism migration preserves old IDs and
history. A rules snapshot is frozen before a new plan starts; legacy plans with an
existing run ID freeze the supplied default with `LEGACY` provenance. Descendant
stage, verification and conflict runs inherit the snapshot. No historic ephemeral
native question can be reconstructed if an older application never saved it.

The tree enforces the remaining organism deadline and charges observed token
increments to the current node's existing budget. Repeated observations from the
same source are deduplicated within a live generation. Providers that do not emit
accountable token usage cannot support a hard token/cost ceiling from this stream;
the implementation does not claim a hard financial spending cap. Legacy planning
stages retain the durable milestone scheduler, worktree and integration lifecycle;
they are not a second unrestricted ordinary child executor.

Application-authenticated final-verification, merge, delivery-conflict and planning
aliases now have separate durable auxiliary-run records. Each record binds the
native alias and request to its actual owner's generation and plan run. Cumulative
usage is deduplicated persistently and charged to that owner's existing token
budget. Live and unknown aliases consume the same runtime capacity limit and obey
the remaining organism deadline. They do not create ordinary session nodes or
acquire additional lifecycle authority. Owner stop and shutdown include these
aliases; failed process reconciliation stays `UNKNOWN` and prevents a new owner
generation, terminal owner observation or history deletion until cleanup succeeds.
An authenticated planning alias can use the owner's ordinary child-creation tools.
The app binds each child to that exact initiating runtime scope while preserving
its logical parent in the aggregate. The planning alias closes admission and joins
its children before finishing; another simultaneous alias cannot substitute for
that runtime ownership.

Confirmed plan attempts now have an application admission hook and a captured
`sessionGeneration`. Admission saves the binding before native startup. A native
stage turn returns to `PENDING`; only the scheduler's accepted, integrated
checkpoint completes the stage node and stores an accepted result. The Plan and
organism stores remain separate: `pendingSessionProjections` is saved atomically
with an accepted Plan checkpoint, and retries replay only its projection. A
projection failure cannot downgrade the accepted stage or repeat its native/Git
work. Startup retries pending projections, including completed plans. The Plan
scheduler still owns merge/final-delivery checkpoints; auxiliary runtime records
track their actual process lifecycle and owner resource usage separately.

The `CodingRuntime.reconcile` interface has a default no-op for adapters and test
fixtures. A custom adapter that does not implement truthful process reconciliation
cannot prove native termination merely because this method returned. Desktop
adapters use their saved process ownership records and transport acknowledgements.
Coroutines cannot undo non-cancellable or already committed external effects.

The stores assume one application instance owns their state. Native provider
conversation history, persisted questionnaire records and organism state are
separate durability domains. Power-loss, filesystem and cross-process locking
guarantees are those of the configured key-value adapter; no cross-domain or
multi-instance ACID claim is made.

## Verification evidence

Focused tests cover exact-call questionnaire recovery, rejected stale generation
and owner, duplicate replies, cancellation, secret omission, pre-commit and
post-commit storage failures, local child routing, in-process lease revocation,
stop cleanup barriers and failed native reconciliation. Session tree tests cover
parent joining, delayed cancellation cleanup, isolated and cancelling failure
policies, dependency ordering, startup failures, stale results, failure during
hydration and question cleanup, and reconciliation before terminal observation.

The combined Gradle verification is reported with the task's final execution
results. `python3 docs/desktop-ui/verify-design-system.py --self-test` passes for the
current changes. UI changes here are state/routing projections; no new visual
component or platform-specific native behavior is claimed.
