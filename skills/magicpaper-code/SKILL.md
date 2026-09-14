---
name: magicpaper-code
description: "Implement or refactor Kotlin/Compose Multiplatform behavior in MagicPaper using its feature API/impl ownership. Use for concrete code changes; use the debug workflow for unexplained failures and code-design for new boundaries."
---

# Implement MagicPaper behavior

Use the [task map](../../docs/agent-workflows/CODEMAP.md) for entry points.
Inspect the contract, implementation and relevant test before expanding
the search. Read the pinned dependency API only when local usage does not settle it.
For straightforward changes, proceed directly from code; skip subtree AGENTS.md
and this skill's full text when the task is unambiguous.

- Turn the request into an observable behavior and identify who owns the state,
  command and side effect. Keep these with the existing feature/service owner.
- Follow the root design-pattern and code-quality rules: reuse the owner's proven
  structure, adapt established patterns to the actual problem, and keep control
  flow cohesive. Do not introduce a custom framework, tangled branching or layers
  without a concrete responsibility just to complete the requested behavior.
- Keep the edit coherent across API, implementation, factory binding and caller
  when the contract changes. Do not expose implementation classes or add another
  root state holder just to make cross-feature access convenient.
- Use component scope for presentation/picker work and application service scope
  for execution and durable drafts. Avoid service lookup inside UI recomposition.
- Preserve serialized identities, resource paths and persisted values during moves.
  Copying a DTO into a new package can change its saved identity.
- Make async results identity-safe. Durable acceptance clears only the captured
  draft version; a restored screen observes an existing run without launching it.
- Prefer the existing Paper API for UI. Apply the UI skill for visual/interactive
  changes; keep implementation descriptions out of user-facing copy.

Use [targeted checks](../../docs/agent-workflows/VERIFICATION.md) for the changed
behavior and its consumers. Add a regression test for meaningful failure/race logic;
do not mirror a private implementation or invent tests for a wording-only change.
Inspect the final diff for unrelated edits and temporary compatibility layers.
Report changed behavior, validation and practical limits.
