# MagicPaper development

## Start with the owner

This repository uses feature `api`/`impl` modules, Decompose navigation and an
isolated Koin application. [docs/MODULES.md](docs/MODULES.md) defines ownership.
Before editing a subtree, read the `AGENTS.md` files between this root and that
subtree; do not assume instructions for a sibling module apply.

Use [the task map](docs/agent-workflows/CODEMAP.md) to locate the owning module,
entry points and checks. Start with its API, the implementation being changed and
the nearest relevant test. Search that module with `rg`; expand to callers when
the contract or evidence requires it. Exclude build outputs from source searches.
Do not read every architecture document or load every skill for a small task.

Current source, Gradle declarations and executable tests establish current
behavior. `docs/PLAN-*`, historical inventories and old verification reports
describe their recorded revision; they are not proof of today's paths or passes.
If a mapped path moved, locate it once and repair the map in the same change.
For library API questions, check the pinned version and local usage first; consult
official documentation when the answer is unresolved or version-sensitive.

## Select a workflow

Read the matching skill when its described task applies. Choose a primary workflow;
add a specialist only for the part of the task that needs it.

| Task | Skill |
| --- | --- |
| Implement or refactor existing Kotlin/KMP behavior | [magicpaper-code](skills/magicpaper-code/SKILL.md) |
| Diagnose a failure or regression | [magicpaper-debug](skills/magicpaper-debug/SKILL.md) |
| Review transitions, races, persistence and side effects | [magicpaper-logic-review](skills/magicpaper-logic-review/SKILL.md) |
| Design module boundaries, public contracts or ownership | [magicpaper-code-design](skills/magicpaper-code-design/SKILL.md) |
| Build or review visual/interactive UI | [magicpaper-desktop-ui](skills/magicpaper-desktop-ui/SKILL.md) |
| Choose, write or run relevant checks | [magicpaper-testing](skills/magicpaper-testing/SKILL.md) |

Canonical skill sources live in `skills/`; `.agents/skills/` contains discovery
links. These are development workflows, separate from the app's installed skills.

## Shared invariants

- API modules expose contracts and values, without Koin or implementation-module
  dependencies. Feature implementations communicate through APIs. `:app` wires
  implementations together; do not recreate a common screen ViewModel/container.
- Constructor injection and explicit factories preserve ownership. Application
  services outlive screen components; leaving a screen must not stop execution.
- Preserve saved IDs, serialized identities, history paths and engine resource
  paths. A refactor of a Kotlin package can be a data migration.
- Restore UI and drafts without sending, saving settings, confirming requests or
  launching new work. External links select existing objects.
- Keep versions in the version catalog and shared Gradle setup in `build-logic`.
  Do not upgrade unrelated libraries to work around an unexplained failure.
- Preserve unrelated working-tree edits. Review affected files before writing;
  avoid replacing files being changed by another task from an old snapshot.

## Design patterns and code quality

- Base design and implementation on established, time-tested patterns and idiomatic
  Kotlin. Reuse suitable project components and library capabilities before creating
  a custom mechanism. Do not reinvent navigation, DI, lifecycle or persistence
  infrastructure already provided by the chosen stack.
- Select patterns by the problem they solve. Combine and adapt their collaborating
  roles within cohesive feature/module boundaries, with explicit contracts and low
  coupling. Preserve each pattern's responsibility; adding a pattern name to a class
  does not establish a sound design.
- Use appropriate patterns such as factories for component creation, repositories
  for persistence boundaries, adapters for platform/provider integration, strategies
  for interchangeable behavior and explicit state transitions for lifecycle logic.
  These are tools to choose from, not a mandatory layer or class for every operation.
- Keep orchestration, business rules, persistence and rendering with their owners.
  Avoid god objects, cyclic dependencies, hidden mutable global state, duplicated
  rules, flag-heavy branching and long methods that mix unrelated responsibilities.
  Control flow and side-effect ownership must be understandable from local contracts.
- Prefer the simplest design that meets the current requirements. Do not replace
  spaghetti code with speculative frameworks, chains of forwarding abstractions or
  pattern ceremony. A clear function or value type is enough when no boundary is needed.
- Explain non-obvious pattern choices and adaptations briefly near the relevant
  contract or in the owner guide. A custom mechanism needs a concrete constraint
  that existing solutions do not meet, with its tradeoffs and verification stated.
- Review structure as well as behavior: cohesion, dependency direction, readable
  names, explicit failure paths and testable boundaries. Working output alone does
  not excuse tangled control flow, accidental coupling or an opaque implementation.

## Error handling

- Never silently swallow an operational failure. Propagate it to an explicit owner
  or handle it there; the handling boundary must log it with operation context.
  Empty `catch`, ignored `Result`, `runCatching(...).getOrNull()` and success-shaped
  fallbacks are unacceptable when they discard an error. Avoid logging the same
  propagated failure at every layer; make the reporting owner clear.
- Logging alone is insufficient when the failure affects a user action, displayed
  result, persistence or background task. Expose an appropriate UI state: field
  validation, an actionable notice, failed task status or a recoverable error view.
  Preserve input and the last valid state, clear obsolete loading indicators, and
  offer retry/correction when safe. Never show success for an unsuccessful operation.
- Give the UI a safe, understandable message and an available recovery action;
  keep technical causes in sanitized diagnostics. Do not display raw exceptions,
  credentials or provider request/response bodies to the user.
- Observe failures from asynchronous jobs, callbacks, flows and persistence queues.
  A supervisor or detached task does not remove the need for an error owner and
  observable completion. Background failures must remain discoverable after navigation.
- Preserve the original cause and distinguish validation, unavailable capability,
  transient failure, corrupted data and unknown external outcome. Retry only when
  semantics permit it; never replace failed reads with empty persisted values or
  blindly repeat a side effect whose outcome is unknown.
- Coroutine cancellation is control flow: propagate `CancellationException` rather
  than converting it into failure or success. Record meaningful cancellation as an
  operation outcome when needed. Cleanup failures must be logged without hiding
  the primary failure or preventing other required cleanup.

## Logging

Use the verbosity order `ERROR -> INFO -> DEBUG -> TRACE`; default to `INFO`.
Higher verbosity includes the preceding levels. Use the project's logging
abstraction and structured events rather than scattered `println` or payload dumps.

| Level | Record |
| --- | --- |
| `ERROR` | Failed operations, unexpected exceptions and broken invariants; sanitized cause, operation identity, consequence and recovery outcome |
| `INFO` | Meaningful user actions and navigation, operation start/result/cancellation, application/runtime lifecycle and configuration-change events |
| `DEBUG` | Significant branches, selected strategy and reason, retry/fallback decisions, relevant state transitions and allowlisted effective configuration |
| `TRACE` | Explicitly enabled, detailed diagnostics; request/response content only here, after redaction and size limits |

- Make it possible to reconstruct the user's path and the application's decisions:
  correlate action, route/visit, feature command, strategy, external attempt and
  result. Use timestamps, feature/component, event name, operation/correlation ID,
  applicable opaque entity/request IDs, attempt/generation and elapsed time.
  Preserve correlation across asynchronous boundaries; distinguish separate attempts.
- Log significant branches with the decision and reason, not just "entered method".
  Record configuration changes and relevant effective options through an allowlist;
  do not serialize entire settings/profile objects. Prefer IDs over titles, names
  or free-form user text. Avoid per-keystroke and per-token logging at normal levels.
- Do not log request/response bodies, prompts, message text, questionnaire answers,
  attachment contents or raw protocol frames at `ERROR`, `INFO` or `DEBUG`.
  Normal request diagnostics contain safe metadata such as operation type, provider,
  model, status, duration and correlation ID. Apply the same rule to URLs, query
  strings, headers and exception messages that may embed request content.
- `TRACE` is opt-in for diagnostics and must not be enabled automatically after an
  error. It does not permit secrets: redact API keys, authorization/cookie headers,
  subscription tokens and secret form fields at every level, including exception
  causes. Bound payload size and log retention; construct detailed payloads lazily
  only when that level is enabled. Logging failure must not corrupt application
  state or replace the original error; use a safe fallback diagnostic sink.

## MagicPaper UI

For interface work, read and apply `skills/magicpaper-desktop-ui/SKILL.md` and
`docs/desktop-ui/BRANDBOOK.md`. All new reusable visual and interactive components
belong to `:designSystem`; application screens and plugins consume its Paper API.
Run `python3 docs/desktop-ui/verify-design-system.py --self-test` and relevant
Gradle checks. Keep user-facing copy limited to labels, actions, results,
validation and information needed for decisions; implementation explanations
belong in code.

By default, center button content horizontally and vertically within the hit area;
center an icon and label together as one group. Use a different alignment only
when an explicit component or platform guideline requires it.

- For a new screen or substantial layout change, make the composition visible
  before implementation: use a wireframe, existing render with marked regions, or
  a small preview. Show hierarchy, primary action, alignment guidelines, proportions
  and narrow-window behavior. A small local adjustment can use its existing render.
- Actively use Compose tooling during design and acceptance. Add or update named
  `@Preview` cases for new or meaningfully changed components/screens, using real
  composables with isolated fixture state. Cover applicable default, empty, loading,
  error, disabled/selected, long-content, narrow and large-text cases. Inspect the
  renders; an annotation or successful compilation alone is not visual acceptance.
  Use interactive preview for supported interactions and Compose render/semantics
  tests when IDE tooling is unavailable. Link the preview symbol/group and state
  matrix in the acceptance handoff; keep native-platform checks separate.
- Use balanced proportions and the golden ratio as a starting point for suitable
  major content relationships. Preserve readable text, useful content widths, hit
  areas and adaptive layout ahead of a rigid numeric ratio.
- Align related headings, fields, lists and action groups to shared guidelines
  (leading edges, text baselines and trailing action edges). Use Paper spacing
  tokens and deliberate nesting; avoid unrelated per-component offsets.
- Measure contrast against the actual rendered surfaces and states. Use at least
  4.5:1 for normal text, 3:1 for qualifying large text, and 3:1 for essential control
  indicators/graphics; distinguish meaningful states with more than color alone.
- Apply Material Design principles of hierarchy, coherent components, adaptive
  layout and feedback through Paper API. Platform conventions govern window chrome,
  focus, menus, Back, dialogs and standard input behavior. Keep macOS, Windows,
  Linux, Android and browser expectations explicit where the task affects them.
- Make interactions understandable through familiar controls, placement and behavior.
  Use concise labels and accessibility names; explanatory prose must not compensate
  for an unfamiliar or ambiguous interaction. Preserve necessary validation and
  information for consequential decisions.
- Minimize the steps to the user's actual goal. Make frequent primary actions
  directly reachable and reuse valid context/selection. Do not introduce keyboard
  shortcuts as a way to simplify a flow or require users to memorize combinations.
  Compare the visible pointer/touch path before/after, including unnecessary dialogs
  and menus; retain necessary confirmation for destructive or consequential actions.
  Preserve keyboard accessibility through focus navigation and standard activation,
  and preserve OS/browser-owned input behavior.
- Treat smoothness and responsiveness as acceptance criteria. Keep I/O and expensive
  computation off the UI thread; avoid unnecessary recomposition, relayout and work
  per streamed token. Aim to match the display refresh rate during scrolling and
  transitions, with prompt input feedback, interruptible animations and reduced-motion
  support. Measure realistic interactions; static previews do not establish FPS.
- Direct acceptance to the changed places: provide the route and reproduction
  steps, platform/window/text scale, a link to the actual render, named regions or
  callouts, expected visual/interaction results, and any unverified state/platform.
  Inspect the implemented UI and correct visual defects before claiming completion.

## Verification and review

Choose checks from [the verification map](docs/agent-workflows/VERIFICATION.md).
Run affected-owner checks first; expand for changed public contracts, shared
infrastructure or platform behavior. Keep live engine integrations opt-in.
Report the behavior changed, evidence, and unverified platforms. A build, a render,
and an installed-platform test establish different things.

In code review, prioritize lost data, duplicate side effects, wrong identity,
runtime cancellation, dependency leaks and inaccessible UI. Give a concrete
trigger and consequence for a finding. Do not weaken safety assertions to make
an unexplained or pre-existing failure disappear.
