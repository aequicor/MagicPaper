# Task worktrees

`TaskWorktreeService` owns task workspace state independently of a screen and of
native runtime generations. `TaskWorkspace` is its platform port. JVM supplies
`GitTaskWorkspace`; other hosts supply `UnavailableTaskWorkspace`. The application
graph shares the existing `PlanningWorkspace` writer leases with the task owner.

The per-session preference defaults to enabled. Effective availability and the
accepted request's mode are distinct: a missing Git capability disables new task
isolation, but never moves an already isolated task to the source checkout.
Legacy pending requests and plans have a null mode and retain their old behavior.

## Task boundary

`CodingRunCheckpoint.runId` (or `Plan.runId`) is the stable task identity. Native
restart, clarification, questionnaire answers and Stop/Continue do not create a
new worktree; each of them is a run start and may bring the existing copy onto the
current destination tip (see Destination distance). A subsequent completed-task
request gets a new branch at the current source HEAD. Each session has one
directory slot; only a clean, completed slot whose branch and commit match the
recorded receipt may be reused.

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

The native response is saved in the task record before delivery, so a restart
can finish Git without asking the model to repeat work or losing its answer.
Neither a final engine event nor a saved response proves Git delivery.

## Git effects and recovery

| Saved phase | Next operation / recovery |
| --- | --- |
| PREPARING | Create or validate the exact recorded branch and directory |
| RUNNING / READY | Continue the same task; READY is an authenticated handoff. A transfer stopped by a conflict stays in the copy for the resumed agent to resolve on the working branch |
| CAPTURING | Capture remaining changes; reuse existing agent commits |
| MERGING | Bring the copy onto the recorded destination tip and verify |
| CONFLICT | Agent repairs the managed copy; questions retain this workspace |
| DELIVERING | Fast-forward the original branch; reconcile ancestry and a clean checkout after a crash |
| COMPLETE | Persist final response and release the slot for the next task |

Persist each phase before its external effect. Store the source branch and commit
at creation, rather than consulting the currently selected UI project at delivery.
Revalidate the source branch, HEAD, index and working files before fast-forward;
never stash, force-reset or push. Failed or uncertain workspaces cannot enter the
pool. Source changes or an advanced destination leave a recoverable checkpoint.

Integration replays the task commits onto the destination tip (`git rebase`) instead of
merging the tip in, so delivery keeps the destination history linear and a change that is
already upstream is dropped instead of conflicting. A crash between the replay and the saved
receipt stays recoverable: the copy keeps a `refs/magicpaper/task-pre-integration-*` ref, an
interrupted replay without a saved CONFLICT phase is aborted and repeated, and a replay left in
CONFLICT is continued after the agent stages its resolution. A merge left in progress by an
older application version is still completed, not discarded.

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
retain the merge phase and the saved response; retrying recovery or continuing
after a restart completes delivery without repeating native execution.

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
`permanentlyBusySourceFolderKeepsTheSavedResultRecoverable`), `SessionCodingWorkspaceTest`
(`staleSourceLeaseYieldsOnlyToProvenNativeStop`),
`CodingSystemPromptsTest.destinationDistanceAndPreRunUpdateReachTheAgent`,
`CodingSystemPromptsTest.pendingTransferConflictIsResolvedOnTheWorkingBranchInPlace`,
`GitTaskWorkspaceTest.preRunUpdateLeavesTheConflictOnTheWorkingBranch`,
`GitTaskWorkspaceTest.captureFinishesATransferTheAgentResolvedButLeftUncontinued`,
`GitTaskWorkspaceTest.restartWhileRunningKeepsTheWorktreeAndActualizesItsBranchOnResume`,
`CodingWorktreeTest.concurrentCompletionsSerializeTheirMergesPerProject`,
`PlanningExecutionServiceTest.worktreePlanDeliversOnlyAfterAcceptanceAndUsesIsolatedSource`,
`PaperMenuToggleInfoTest` and
`CodingComposerRenderTest.plusMenuExposesWorktreeAndKeepsInfoAvailableWhileLocked`.
Use temporary repositories and fake engines; live Pi/Codex tests remain opt-in.
