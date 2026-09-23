---
name: magicpaper-code-design
description: "Design or assess MagicPaper module boundaries, public contracts, dependency direction and state/resource ownership. Use when introducing APIs, splitting services or changing lifecycle; ordinary local edits do not require an architecture exercise."
---

# Design within the module architecture

Read the owner AGENTS.md, and the Class structure section of the root AGENTS.md
only when the boundary change spans multiple modules. Start from the concrete behavior and
existing owners. Preserve the user-requested scope; make routine implementation
choices without a separate approval ceremony.

For a meaningful boundary change specify:

- Owning module and consumers; public input/state/action/output/factory contracts.
- Established pattern(s) that solve the actual problem, their collaborating roles,
  and any adaptation required by this feature's constraints.
- Dependency direction and the smallest ports consumers actually need.
- Lifetime of components, services, clients, subscriptions and persisted state.
- Error/cancellation behavior, side effects and migration compatibility.
- Checks that establish the new contract on its relevant platforms.

Feature APIs do not export Koin or depend on implementations. Only root assembly
connects all implementations. Do not solve cycles by moving services into
`core:model`, exposing a catch-all service, or returning implementation objects
through a nominal API. Prefer constructor injection and ordinary Koin DSL.

Keep screen rendering and screen-only jobs with components, durable entity drafts
and long-running work with services, navigation decisions in the root coordinator.
Use visit IDs for presentation and entity/request versions for editable data.
Platform hosts create retained contexts outside recomposition; previews use fakes.

For UI capability design, apply the Paper UI skill and extend `designSystem` with
a narrow public API. For persisted or engine-facing types, verify old serialized
identities and resource paths before moving declarations.

Choose the least complex design that satisfies these constraints. Introduce a
new abstraction for a real owner/contract boundary, not speculative future reuse.
Apply the root AGENTS.md section "Design patterns and code quality": check existing
components and library mechanisms first, combine proven patterns by responsibility,
and keep their roles within the module ownership graph. Explain why a custom
mechanism is necessary if established solutions do not fit. Reject both tangled
control flow and unnecessary layers added merely to exhibit a pattern.
Document decisions that affect future edits in the owning AGENTS.md; avoid
duplicating class inventories or transient test statuses.
