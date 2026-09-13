# Application composition and navigation

`:app` is the application composition module in this directory. Only platform
hosts depend on it; features expose APIs and retain their own implementations.
Do not restore a `:shared` module or redirect its old Gradle path to `:app`.

Entry points under `src/commonMain/kotlin/io/aequicor/magicpaper/`:
`di/AppRuntime.kt`, `di/Dependencies.kt`, `navigation/AppRoot.kt`,
`navigation/RootComponent.kt`, `navigation/NavigationJournal.kt`, and `App.kt`.

- One isolated `koinApplication` owns clients, repositories and background services.
  Startup orders storage/migrations, wiring, restoration/background work, then UI.
  Factories create screen components; never resolve a new application graph during
  recomposition. Start and close are idempotent, including partial startup failure.
- The journal is navigation's source of truth. Decompose `ChildStack` projects its
  active prefix; do not add competing `UiState` routes or a second browser adapter.
- A→B→A has distinct visit IDs. Back/Forward moves the cursor; a new branch truncates
  Forward; selecting the current route adds no visit. Preserve the complete journal.
- Native Back closes `ChildSlot` first. Browser history changes close the dialog
  and perform their requested transition. Late dismissal must match dialog identity.
- Restore before applying an external link; defer the link during onboarding.
  Missing entities show a result. Disabled plugins need explicit enablement.
- Persist view state by visit and editable drafts by entity. Keep secrets out of
  generic presentation snapshots. Hiding the sidebar only hides that panel.
- Android recreation retains runtime ownership. Desktop acquires its instance lock
  before DI. Web owns one history bridge and an independent journal per tab.

Use separate fake assemblies for previews/tests. Host changes also require the
platform rows in `docs/agent-workflows/VERIFICATION.md` (path from repository root).
