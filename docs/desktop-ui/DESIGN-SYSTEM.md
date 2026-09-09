# `:designSystem` — public Paper API

`designSystem` is a Compose Multiplatform module with JVM, Android, JS and Wasm targets. It has no dependency on `:shared`; `shared` depends on it. Its public namespace is `io.aequicor.magicpaper.designsystem` and contains no Material types in public parameters, returns or composition locals.

## Inventory

| Need | Public API |
|---|---|
| Theme, palette, typography, spacing | `PaperTheme`, `PaperColors`, `PaperTypography`, `PaperSpacing` |
| Text and surfaces | `PaperText`, `PaperSurface` |
| Actions | `PaperButton`, `PaperIconButton` |
| Input and selection | `PaperField`, `PaperSwitch`, `PaperChoice` |
| Overlays | `PaperMenu`, `PaperDialog`, `PaperTooltip` |
| Feedback and structure | `PaperProgress`, `PaperDivider`, `PaperList`, `PaperScrollArea` |

`PaperPlatformPolicy` is selected once through `rememberPaperPlatformPolicy()`: macOS uses compact 28 dp controls, Windows/Linux 32 dp, Android and web use a 40–48 dp compatible fallback. Call sites receive semantic data, not OS APIs.

## State and accessibility catalogue

`PaperStateCatalog` is the canonical catalogue and is verified by `PaperStateCatalogTest`. Buttons, switches, choices, menus and dialogs cover normal, hover, pressed, focused, disabled and selected; buttons additionally cover busy, while fields additionally cover error. A field publishes its error with accessibility semantics; actions receive a content-description label. `PaperButton` consumes Enter on key-down and Space on key-up when focused, so it has one deterministic keyboard activation path. `PaperFocusAnchor` registers a live opener with `PaperFocusRestorer`; `PaperDialog` restores it after dismissal and skips a disposed opener in favour of the most recent live fallback. `PaperSemanticsTest` sends real headless Enter/Space key events, checks accessible labels/error semantics, and dismisses a dialog to assert focus restoration; the focused macOS/Windows manual pass remains required.

The parchment palette and Cormorant Garamond, Literata and JetBrains Mono font resources are owned by this module. Material 3 is private implementation only; the current `MagicPaperTheme` is a deprecated forwarding compatibility entry point while later migration stages move feature call sites to Paper APIs.
