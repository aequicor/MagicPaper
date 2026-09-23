---
name: magicpaper-testing
description: "Select, implement and interpret MagicPaper tests across feature owners, runtime/DI, persistence, Paper rendering and platform hosts. Use for test work or a nontrivial verification matrix; avoid full-suite repetition for low-impact edits."
---

# Verify the changed contract

Select the affected owner and layers. Tests live beside their owner;
cross-feature assembly is allowed in tests without production dependency leaks.
For simple changes, run only the owner's test; skip the wider checks
unless the change affects shared contracts or infrastructure.

- Test observable behavior at the boundary that can fail: persisted record after
  reopen, transition after Back, native cleanup before lease release, or a real
  semantic action. Avoid tests that only duplicate implementation expressions.
- Use independent fake assemblies, temporary storage/repositories and controlled
  coroutine scheduling. Do not exercise the user's data or active native sessions.
- Reopen storage with a fresh owner for restart tests. Test failed writes and late
  completions for changed persistence/lifecycle logic. Keep secrets out of fixtures
  derived from user data and out of diagnostic output.
- For Compose, install the owning Paper renderer providers. Use UI-thread helpers,
  observable conditions and actual semantics; inspect renders for layout changes.
- Use the UI skill's [Compose preview workflow](../magicpaper-desktop-ui/references/compose-previews.md)
  to assess the real component's named state cases, sizes and text scales. Report
  which cases were visually inspected; an IDE preview or simulated Paper policy
  is not a native-platform test. Use a render harness if IDE tooling is unavailable.
- Start with the focused failing/new test, then the affected owner's suite. Expand
  to consumers and platforms when contracts, shared infrastructure or interop change.
- Keep one run of a particular test-task active at a time. Retain command and log
  identity so a later filtered run does not overwrite the evidence being compared.

Distinguish compiler, linker, browser, host and installed-package evidence.
`NO-SOURCE` and skipped tests do not establish behavior. Live engine tests are
opt-in. Record an unavailable platform/browser as `NOT_RUN` with its reason.

For existing failures, compare baseline reports and exact test behavior without
weakening safety assertions. Report new failures separately. A green filtered run
does not make an unrun or failing full suite green.
