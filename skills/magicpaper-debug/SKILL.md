---
name: magicpaper-debug
description: "Diagnose and fix MagicPaper build failures, runtime exceptions, incorrect behavior or regressions. Use when the cause is uncertain; distinguish migration regressions, existing failures and environment limitations."
---

# Diagnose a failure

Start with the failing command or user-visible trigger, first actionable error,
source set and owner from the [task map](../../docs/agent-workflows/CODEMAP.md).
Use existing logs and the matching test before a full rebuild or repository scan.

1. Establish expected versus actual behavior and a bounded reproducer. Classify
   whether failure occurs in compilation, linking, storage, runtime or rendering.
2. Follow the failing symbol into its owner and direct calls. For module moves,
   check API visibility, Gradle source-set dependencies, renderer providers,
   resource paths and fixture lifetime before changing production behavior.
3. Form a testable cause and collect evidence at its boundary. For races, record
   entity/request/generation identity and operation order, not secret payloads.
4. Fix the cause and rerun the reproducer, then affected-owner/consumer checks.
   Avoid catch-and-empty fallbacks, blind retries and timing increases that hide it.

An `UNKNOWN` native result must not become success or a repeatable action merely
to satisfy a test. A cancelled coroutine is not a storage error. A failed decode
must not trigger a blank write over the original record.

If a failure appears pre-existing, compare the exact test on the baseline with
matching environment and fixtures. Preserve unrelated failures and state the
evidence; do not silently edit assertions or mark them passed. Missing browser,
native OS or cached dependency is an environment limitation with a concrete next
check, not proof of a source regression.

Use the [verification map](../../docs/agent-workflows/VERIFICATION.md) to bound the
rerun. Finish with cause, change, reproducer result and remaining uncertainty.
