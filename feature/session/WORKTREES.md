# Task worktrees

`TaskWorktreeMachine` in `:magic-agent:workspace:api` owns task transitions.
`DefaultTaskWorktreeOwner` in workspace impl journals inputs and executes
`TaskWorkspace` effects independently of screens and native runtime generations.
The JVM application supplies `GitTaskWorkspace` and the owner. The runtime's
`TaskWorktreeService` coordinates parent admission, writer leases and child
projections through API ports; it cannot overwrite the child's state. These
modules are absent from Android and browser targets.

The per-session preference defaults to enabled. Effective availability and the
accepted request's mode are distinct: a missing Git capability disables new task
isolation, but never moves an already isolated task to the source checkout.
Legacy pending requests and plans have a null mode and retain their old behavior.

## Task boundary

`TaskWorktree.taskId` is the stable task identity. `CodingRunCheckpoint.workspaceTaskId`
binds a fresh native `runId` to that task; old serialized checkpoints keep the
nullable default. Clarification and an explicitly admitted continuation may reuse
the copy, but never reuse a native attempt's identity. A new parent generation must
bind explicitly and invalidates the previous handoff. Unknown child outcomes block
admission even when the parent has a newer generation. A subsequent completed-task
request gets a new branch at the current source HEAD. Each session has one directory
slot; only a clean, completed slot whose branch and commit match the recorded receipt
may be reused.

Branch and commit names are meaningful and ASCII. The task branch is
`magicpaper/worktree-<slug>`: a transliterated, ref-safe slug of the task request
(`TaskWorktree.label`), with a short task hash appended only when that name is
already taken in the source repository. The delivery commit subject is the
transliterated request line, and the task identifier stays in the commit body
instead of being the whole message. Legacy records without a label fall back to
the identifier. Planning stream branches use the same application prefix:
`magicpaper/<purpose>-<path hash>`; the `codex/` prefix is gone from every branch
the application creates.

Ordinary coding must submit `task.handoff` through the normal tool/receipt path.
Both native engines receive the active task's delivery policy in their system
instructions, including when resuming history. A clarification is saved to the
session history the moment it is accepted, so a relaunch that is blocked — by a
quarantine of an interrupted tool, for example — keeps the user's message for the
next continuation instead of losing it; the blocked relaunch opens the recovery
dialog instead of failing into a duplicated error. Worktree mode authorizes automatic
delivery; the agent must not ask for merge/finish/pause confirmation. A successful
handoff explicitly tells it to finish its response, without claiming delivery has
already happened. Genuine missing requirements still use the questionnaire.
The tool is available only for an isolated CODE task, and validates task and
generation at persistence. RESULT is necessary but not sufficient: runtime and
child writers must have stopped, questions must be answered and checks must pass.
Clarification revokes a handoff before capture. After capture starts, new sends
are queued as another task. Stop retains the current checkpoint and worktree.

The native response is saved in the task record before delivery. Restoring it never
starts a model or Git operation. Neither a final engine event nor a saved response
proves Git delivery.

## Git effects and recovery

| Known phase | Explicitly admitted next operation |
| --- | --- |
| PREPARING | Create or validate the exact recorded branch and directory |
| RUNNING / READY | Continue the same task; READY is an authenticated handoff. A transfer stopped by a conflict stays in the copy for the resumed agent to resolve on the working branch |
| CAPTURING | Capture remaining changes; reuse existing agent commits |
| MERGING | Bring the copy onto the recorded destination tip and verify; failed checks go back to the agent |
| CONFLICT | Agent repairs the managed copy; questions retain this workspace |
| DELIVERING | Await the exact operation's durable outcome; restore never repeats delivery |
| COMPLETE | Persist final response and release the slot for the next task |

Persist each intent before its external effect. The journal contains opaque input
envelopes; private payloads retain source paths, responses and arguments. A successful
port result is saved as an immutable receipt before its terminal fact is appended.
If the fact is lost, an explicit inspection can recover that exact receipt without
running Git. A missing receipt leaves the operation UNKNOWN. Git ancestry and a dead
parent PID alone cannot prove all writers stopped; the real Git port does not turn
those observations into permission to repeat a command. Legacy active snapshots
without a journal are likewise UNKNOWN, including RUNNING, MERGING and DELIVERING.

Store the source branch and commit
at creation, rather than consulting the currently selected UI project at delivery.
Revalidate the source branch, HEAD, index and working files before fast-forward;
never stash, force-reset or push. Failed or uncertain workspaces cannot enter the
pool. Source changes or an advanced destination leave a recoverable checkpoint.

Integration replays the task commits onto the destination tip (`git rebase`) instead of
merging the tip in, so delivery keeps the destination history linear and a change that is
already upstream is dropped instead of conflicting. The copy keeps a
`refs/magicpaper/task-pre-integration-*` ref. A known CONFLICT outcome permits an
explicit repair attempt after the agent stages its resolution; an interrupted
integration without a completion receipt remains UNKNOWN and is not aborted or
repeated during restore. Existing Git state is retained for inspection.

## Merge turn serialization

Several tasks of one project can finish at once, and the expensive verification between the
rebase and the fast-forward delivery is exactly the window in which a sibling delivery would
invalidate the merge. `TaskWorktreeService` therefore queues whole merge turns per source
folder: reading the destination tip, replaying the task, verification and delivery run as one
serialized turn, so a waiting task always reads and merges the tip its predecessor just
delivered instead of a stale one and rebases only once. Conflict resolution releases the turn:
an agent repair and a user clarification do not block sibling merges, and the repaired task
re-enters the queue to merge onto the fresh tip. A destination advanced by an out-of-turn
writer (the user, another application process) is still detected at delivery and re-merges.
An unresolved conflict on the same tip that a repair already addressed is a terminal error
instead of another repair round.

## Shared writer leases

Task preparation and branch delivery touch the source checkout, so they take the same
`PlanningWorkspace` writer lease as an ordinary run in that folder. A held folder is a bounded wait,
not a cascade: `TaskWorktreeService` first asks the runtime to reclaim a retained lease whose
session no longer owns a working area — the engine's reconciliation is the proof, never a UI status —
then polls, and only afterwards reports `TaskWorkspaceBusy` naming the folder. The saved phase and
response let the next continuation finish the Git operation without another native run. An uncertain
scope close keeps the lease until that proof exists; resuming the same session reconciles its
previous generation and releases it. Every session start retries that release and keeps retrying
inside a bounded wait, so a restore that was rejected while the stale retention still held the
folder does not loop forever, and a direct code root waits for the same window instead of failing
on the first busy answer. A lease entry whose OS lock is already dead — debris of a failed cleanup —
never blocks the folder: the port prunes it on the next acquisition. Timeout reports
`TaskWorkspaceBusy` naming the folder and logs the holding owner for diagnosis. Writer leases are
exclusive per canonical checkout: its `owner.lock` file also excludes a parallel application
instance on the same folder, while unrelated folders of the same profile stay usable.

## Destination distance

A task can outlive the destination tip it started from, and an agent editing a stale copy
repeats work the destination already contains. `TaskWorkspace.refresh` measures the distance
read-only (`git rev-list --left-right --count HEAD...refs/heads/<target>`) and, when that is
safe, brings the copy onto the destination tip. The session owner calls it once per run start
for a task that is still RUNNING/READY, under the writer lease of the managed copy; a
folder owned by another delivery only postpones the update. A PREPARING copy is left on its
recorded base commit so replaying preparation stays possible.

The update is declined, never forced: unsaved agent edits are not committed on the agent's
behalf, and a failure without conflict paths is rolled back with `git rebase --abort`. A
transfer stopped by a conflict is not rolled back: it stays in the managed copy on the working
branch, the task record marks it (`pendingTransfer`, with the recorded destination tip as the
integration point so reconcile accepts the resolved branch), and the resumed agent resolves
the conflict in place (`git add`, then `git rebase --continue`) before continuing the task.
Capture finishes a transfer the agent resolved but did not continue, and refuses one with
unresolved paths. A declined update keeps the measured distance and its reason in the task
record; the remaining integration happens at delivery. Delivery never depends on a successful
update.

`behindCommits`, `refreshNote` and `integratedCommit` are reported to the user as a session
status and to the agent in the worktree instructions, so a resumed run re-reads files instead of
trusting saved line numbers. Distance is metadata: on its own it authorizes no Git effect.

Checks declared by the agent run through `SessionIntegrationCheckRunner`, which
uses the existing protected research runner. There is no unrestricted shell
fallback. Plan acceptance additionally verifies the merged tree through the
existing acceptance machinery. A model cannot replace registered acceptance
checks with its own declaration.

Verification snapshots recognize index mode `160000` as a gitlink. Empty,
uninitialized submodule directories are valid; initialized submodules include
their HEAD, index and actual tracked/untracked bytes recursively. An ignored
submodule status does not hide its changes from verification, and recursion is
bounded by depth. An untracked nested repository, which Git itself enumerates as
a single entry, contributes one marker instead of its bytes. Snapshot failures
retain the merge phase and the saved response.

The checks are the ones the agent handed off, so a known failed check of an ordinary
task run goes back to that agent instead of ending the session, as a conflict does:
the parent opens a repair request (`BeginRepair`, admitted at `running-checks-failed`),
the task returns to RUNNING (`ReturnForRepair`), and the agent receives the check
report — a failed command, one that could not start, or checks that changed the
task's files. Its next handoff is captured and merged like the first. Three such
rounds per run bound an agent that cannot make its checks pass; the last refusal
then fails the run with the report as the task error. A repair that ends without
a new handoff is refused by the capture with the task's own reason and stays with
the agent for the next continuation. Plan acceptance keeps its own verification.

A known failed check then permits the explicit `RetryVerification` transition; delivery
still requires a new successful verification and acceptance, and a failure on that retry
is returned to the agent again. An interrupted check with an unknown outcome cannot
use this transition. Failed lease release is retried separately, including after
delivery reached COMPLETE, without repeating Git.

The existing `GitPlanningWorkspace.apply` remains a file transfer for legacy
plans. New isolated plans transfer accepted stage output into the task worktree,
then use branch delivery once for the whole task. Explicitly disabling worktree
selects direct execution; it does not loosen legacy workspace validation.

## UI and checks

`CodingSessionUi` derives selected/locked state from the active task or accepted
request before falling back to the preference. The composer consumes
`PaperMenuToggleInfo`; its information action remains available when the setting
is disabled. While a task runs, the session shows its delivery phase, or the
measured distance to the destination branch when the copy could not be updated.
Previews: `PaperMenuToggleInfoPreview`, group **Menu setting**.

Focused tests: `GitTaskWorkspaceTest`, `CodingWorktreeTest`
(`newTaskWaitsForTheSourceFolderInsteadOfFailing`,
`permanentlyBusySourceFolderKeepsTheSavedResultRecoverable`,
`failedChecksAreReturnedToTheAgentAndItsRepairIsDelivered`,
`checksThatKeepFailingStopAfterBoundedRepairsAndAnExplicitRetryDeliversWithoutTheAgent`,
`aChecksRepairWithoutANewHandoffStopsWithItsReasonAndContinuesTheSameTask`), `SessionCodingWorkspaceTest`
(`staleSourceLeaseYieldsOnlyToProvenNativeStop`),
`CodingSystemPromptsTest.destinationDistanceAndPreRunUpdateReachTheAgent`,
`CodingSystemPromptsTest.pendingTransferConflictIsResolvedOnTheWorkingBranchInPlace`,
`GitTaskWorkspaceTest.preRunUpdateLeavesTheConflictOnTheWorkingBranch`,
`GitTaskWorkspaceTest.captureFinishesATransferTheAgentResolvedButLeftUncontinued`,
`GitTaskWorkspaceTest.restartWhileRunningKeepsTheWorktreeAndActualizesItsBranchOnResume`,
`CodingWorktreeTest.newPromptKeepsLegacyUnknownTaskAndDoesNotLaunchNative`,
`CodingWorktreeTest.foreignCheckpointCannotAuthorizeLegacyUnknownTask`,
`CodingWorktreeTest.legacyDeliveryWithoutReceiptDoesNotInferSuccessFromGitState`,
`CodingWorktreeTest.completedDeliveryRetriesFailedLeaseCleanupWithoutRepeatingGit`,
`CodingWorktreeTest.concurrentCompletionsSerializeTheirMergesPerProject`,
`PlanningExecutionServiceTest.worktreePlanDeliversOnlyAfterAcceptanceAndUsesIsolatedSource`,
`PaperMenuToggleInfoTest` and
`CodingComposerRenderTest.plusMenuExposesWorktreeAndKeepsInfoAvailableWhileLocked`.
Use temporary repositories and fake engines; live Pi/Codex tests remain opt-in.
