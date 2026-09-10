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

## Resource settings, plan budgets and explicit retry

The September 10 early-stop failure came from a fixed 64,000-token stage grant:
four read/search operations reported 93,956 tokens while the parent still held
926,000. The quota cancellation then lost its cause at the scheduler's event-channel
boundary and appeared as a stream ending without a confirmed result.

The earlier correction in `c5c7279` replaced that fixed grant with fair allocation,
reconciled overruns, preserved cancellation causes and added explicit stage retry.
It still treated a worker's grant as a stopping limit and retained implicit task
ceilings. The current policy supersedes those resource limits.

`AppSettings.agentLimits` is the live resource policy for agent tasks. Each of its
seven configurable ceilings defaults to `null`, meaning no application limit:

| Field | Explicit setting limits |
| --- | --- |
| `activeSessions` | Concurrent ordinary and auxiliary native runs |
| `depth` | Session-tree depth |
| `tokens` | Total reported token use across the task |
| `durationMillis` | Task duration measured from organism creation |
| `retries` | Retries on eligible recovery paths |
| `queueSize` | Context, result and diagnostic queues |
| `contextCharacters` | Transferred context size |

The settings screen leaves unset values empty and allows removing them. Ordinary
pending children and passive immunity do not occupy native runtime slots. An
explicit limit of one therefore permits one live agent. The compatibility field
`recoveryTokens` defaults to zero; it does not introduce a separate stopping limit.

When a token limit is configured, plan admission still divides available parent
tokens among unfunded unfinished selected stages and one retained parent share for
orchestration and verification. Funded siblings are excluded under the aggregate
lock, and continuation turns retain their remaining reservation. These shares are
accounting allocations: a worker whose reservation reaches zero can continue while
the task has unspent tokens. The configured task-wide limit is authoritative.
Without a token limit, reservations remain zero and do not restrict admission;
there is no synthetic maximum-sized token pool.

Provider usage arrives after work and can exceed a reservation or the task limit.
Actual spend is preserved. Excess reservations are removed from the responsible
branch and its ancestors, then other grants, with recovery last. An overrun beyond
the configured task limit leaves no spendable grants without clamping the reported
cost. Already incurred usage from another live session remains accountable after
the limit is reached. Native and auxiliary runs observe the same aggregate spend.

An aggregate with missing or zero `limitPolicyVersion` predates explicit resource
settings. Its hidden limits migrate to the unbounded policy, reservations are
cleared, and version 1 plus an audit event are persisted. Actual spend, history,
results, generations and stop/unknown states are preserved. The service applies
current user settings before admission, including when recovering an aggregate
whose legacy session projection was never completed.

Policy synchronization, saving settings and initial adoption share a service
mutex. An older settings snapshot cannot overwrite a newer saved removal. Token
limit changes redistribute only the remaining allowance after actual spend; other
limit changes leave reservations intact. Live runtime observers replace deadline
timers when settings change and stop work when an explicit task budget is reached.
Removing a limit does not reset spend or reopen a stopped generation. The UI reports
settings-save failures separately from failures applying successfully saved settings
to live tasks.

Eligible format and transport recovery has no default retry-count ceiling.
Repeated recovery yields or waits with backoff and remains cancellable; a configured
retry limit is enforced. Elapsed time alone no longer quarantines an unconfirmed
operation after five minutes, and immunity recreation has no implicit one-minute
cooldown. Unknown external effects still require reconciliation.

A child cancellation preserves its budget/deadline reason and partial tool
evidence. It neither supplies a successful completion event nor authorizes an
automatic retry of uncertain work. Owner/user cancellation still cancels the
scheduler normally.

Explicit retry captures a persisted `StageAttempt.retryAuthorization` for the
exact plan/run/stage/attempt/turn, session generation and node version. Saving it
requires the Plan snapshot to remain unchanged. Admission atomically checks and
consumes its ID, records user authorization, checks any configured resource limits
and advances the stopped worker's generation. Replaying admission before native
startup reuses that generation and reservation. A later stop invalidates the
authorization; active descendants, unknown outcomes, quarantine, archive, accepted
results and a closed parent remain blockers. Old plans decode the optional field
as null. The Plan and organism remain separate storage transactions; retry does
not alter existing worktrees or evidence.

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

The tree enforces only a configured organism deadline or aggregate token limit.
Observed token increments are attributed to the current node and counted in the
task-wide total; a node's reservation is not an independent stopping limit. Repeated
observations from the same source are deduplicated within a live generation. Providers that do not emit
accountable token usage cannot support a hard token/cost ceiling from this stream;
the implementation does not claim a hard financial spending cap. Legacy planning
stages retain the durable milestone scheduler, worktree and integration lifecycle;
they are not a second unrestricted ordinary child executor.

Application-authenticated final-verification, merge, delivery-conflict and planning
aliases now have separate durable auxiliary-run records. Each record binds the
native alias and request to its actual owner's generation and plan run. Cumulative
usage is deduplicated persistently, attributed to that owner and counted toward
any configured task-wide token limit. Live and unknown aliases consume the same
configured runtime capacity and obey a task deadline only when one is configured. They do not create ordinary session nodes or
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

The earlier September 10 planner correction (`c5c7279`) added 20 regression tests for fair allocation,
legacy overrun accounting, concurrent usage after exhaustion, cancellation causes,
durable retry authorization and newer stop/Plan snapshots. All 1,246 shared and
30 design-system tests passed; two existing opt-in live catalog checks were skipped.
Desktop, Android, JS and Wasm compilation passed. Reverting only the two original
faulty code fragments reproduced the 64k grant and both lost cancellation causes
in three failing tests. Counts, commands and control results are stored in
[`planner-budget-retry-2026-09-10.json`](session-infrastructure-verification/planner-budget-retry-2026-09-10.json).
Live application records were inspected read-only; no plan was restarted and the
running desktop application was not replaced.

The current optional-policy change adds regression coverage for exceeding the old
implicit ceilings, explicit limits, use beyond a worker reservation, legacy policy
migration, live deadline/token changes, serialized policy updates and settings
validation. These additions and updated expectations are separate from the
historical verification totals above. Current Gradle, platform and design-system
verification passed: 1,290 shared tests and 30 design-system tests, with the same
two opt-in live catalog tests skipped. Desktop, Android, JavaScript and Wasm
compilation succeeded. The settings form was rendered at narrow and desktop
widths and at 200% text scale; validation and saving were exercised. Commands,
suite totals and verification scope are recorded in
`docs/session-infrastructure-verification/optional-agent-limits-2026-09-10.json`.
