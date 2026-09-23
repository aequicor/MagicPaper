# Claude Code in this repository

Project rules — ownership, module boundaries, class structure, error handling,
logging, UI and verification — live in [AGENTS.md](AGENTS.md). Read it first; it
is the contract. This file holds only what running as Claude Code here adds.

## Checks take minutes, and silence is not progress

Measured wall clock on a warm daemon. Plan the round around these, not around a
hope that a check is quick:

| Check | Cost | What it establishes |
| --- | --- | --- |
| `python3 tools/verify/verify-module-architecture.py --self-test` | ~1 s | Module graph, platform boundary, effect and planning-purity rules. Not a dry run: it scans the real tree and fails every Gradle `check` task |
| `:core:model:jvmTest` | seconds | 196 tests: values, pure rules, machines |
| `:feature:tools:impl:jvmTest` | seconds | Tool catalogue and the access matrix pin |
| `:feature:settings:impl:jvmTest` | ~20 s | 56 tests, including settings renders |
| `:feature:skills:impl:jvmTest` | ~55 s | 147 tests |
| `compileMigrationTargets` | ~25 s warm | Common modules really build for Android, JS and Wasm |
| `:feature:coding:impl:jvmTest` | ~4 min | 1305 tests; the largest owner |
| `:app:jvmTest --rerun-tasks` | ~5 min | 207 integration tests |
| `./gradlew jvmTest --continue` | ~10 min | Every module; the only check that sees a sibling that stopped compiling |

A run that prints nothing for ten minutes is indistinguishable from a stall.
While a long check or a background agent is working, say what is running and
report each result as it lands; do not finish a turn and go quiet until the whole
chain is done. When a run is long enough that the user would otherwise wonder,
watch it and emit progress — elapsed time, the current step, and whether a JVM is
actually consuming CPU. No output and no CPU is a stall, and it must be reported
as one rather than left to look like ordinary waiting.

## Parallel agents share one checkout

Subagents working in this tree at the same time also share its Gradle state. Two
consequences, both of which have bitten:

- A half-written Kotlin file from another agent surfaces in your build as a
  compile error in code you never touched, and the natural reaction is to "fix"
  it. Give concurrent agents disjoint files, serialize anything that builds
  Kotlin, or give an agent its own worktree.
- A check whose tasks come back entirely `UP-TO-DATE` proves nothing about your
  change: another agent already populated that cache. Re-run with `--rerun-tasks`
  before treating a green result as evidence.

Read the result XMLs under `<module>/build/test-results/jvmTest/` rather than the
console summary when the claim matters: grep for failure tags across every
module's directory, so a failure in a task you did not name is not missed.

## The branch carries known failures

The accepted baseline is the Долги section of
[STUDIO-ARCHITECTURE.md](docs/STUDIO-ARCHITECTURE.md). Check a new failure against
it before calling it a regression, and against reality before calling it known —
a debt entry that no longer reproduces is how a real failure gets dismissed.
Never quiet a failure by weakening its assertion.
