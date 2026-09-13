# Desktop host

Start in `src/main/kotlin/io/aequicor/magicpaper/main.kt` and
`DesktopActivationBroker.kt`; packaging belongs to `build.gradle.kts` and `packaging/`.
Application composition and navigation belong to `:app`; this module owns the Desktop host.

- Acquire the user/data-directory instance lock before creating DI. Secondary
  launches forward activation with acknowledgement rather than starting another runtime.
- Create the root context outside recomposition. Activation opens/focuses the
  existing window, including a minimized one; a URI must not create or run an entity.
- Preserve resource-managed protocol registration in native packages. A macOS
  build does not validate Windows/Linux installation, upgrade or uninstall behavior.
- Use broker/unit fixtures and isolated app data for checks. Do not launch a test
  runtime against the user's normal history to validate a window or protocol.

Platform commands and evidence requirements: root `docs/agent-workflows/VERIFICATION.md`.
