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
instructions, including when resuming history. Worktree mode authorizes automatic
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
| RUNNING / READY | Continue the same task; READY is an authenticated handoff |
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

## Destination distance

A task can outlive the destination tip it started from, and an agent editing a stale copy
repeats work the destination already contains. `TaskWorkspace.refresh` measures the distance
read-only (`git rev-list --left-right --count HEAD...refs/heads/<target>`) and, when that is
safe, brings the copy onto the destination tip. The session owner calls it once per run start
for a task that is still RUNNING/READY, under the writer lease of the managed copy; a
folder owned by another delivery only postpones the update. A PREPARING copy is left on its
recorded base commit so replaying preparation stays possible.

The update is declined, never forced: unsaved agent edits are not committed on the agent's
behalf, an unfinished Git operation is left alone, and a conflict before a run is rolled back
with `git rebase --abort` because nobody is there to resolve it. A declined update keeps the
measured distance and its reason in the task record; the remaining integration happens at
delivery. Delivery never depends on a successful update.

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

Focused tests: `GitTaskWorkspaceTest`, `CodingWorktreeTest`,
`CodingSystemPromptsTest.destinationDistanceAndPreRunUpdateReachTheAgent`,
`PlanningExecutionServiceTest.worktreePlanDeliversOnlyAfterAcceptanceAndUsesIsolatedSource`,
`PaperMenuToggleInfoTest` and
`CodingComposerRenderTest.plusMenuExposesWorktreeAndKeepsInfoAvailableWhileLocked`.
Use temporary repositories and fake engines; live Pi/Codex tests remain opt-in.
