# Session execution

Start in `impl/src/commonMain/kotlin/io/aequicor/magicpaper/`:
`ui/DefaultChatService.kt` and `ui/DefaultCodingService.kt` handle chat and project commands; `domain/SessionTreeRuntime.kt`
owns runtime relationships; `domain/OrchestrationService.kt` handles planning.
Native adapters are under `impl/src/jvmMain`; engine resources keep `coding/*`
classpath names. Public access belongs to `CodingService` and other API ports.

- Navigation, component destruction and application shutdown are distinct from a
  user's explicit stop. Keep native cleanup, checkpoints and reconciliation semantics.
- Check project/session identity, owner generation and request identity at the
  side-effect boundary. Late output must not mutate a newer run or selection.
- An unknown external outcome is not permission to repeat it. Resolve saved
  evidence before another launch, confirmation, integration or destructive effect.
- Preserve source-workspace leases until actual execution and cleanup allow
  release. Do not use a UI status change as proof that a process has stopped.
- A restored checkpoint/draft must not duplicate accepted input. Clear a composer
  only after durable acceptance of the captured version; preserve newer typing.
- Use local fixtures and temporary repositories. Live Pi/Codex checks are opt-in;
  never use the user's active projects or application history as disposable fixtures.

For native engines and protocols, read `backend-agents/README.md`.
