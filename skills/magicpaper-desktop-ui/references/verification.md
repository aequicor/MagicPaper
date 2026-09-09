# Verification and evidence

Read this reference before declaring a UI task complete.

## Select checks by risk

- Always run the deterministic desktop UI contract checks and `git diff --check`.
- Run focused DS/component tests and tests named by `docs/desktop-ui/CONTRACT.md` for behavior touched by the change.
- Compile every affected target. A successful JVM build does not prove Android, JS, or Wasm compatibility.
- For dialogs, windows, focus, keyboard, scaling, accessibility, IME, drag/resize, Snap, or menus, capture separate platform evidence where available.
- Preserve scroll anchors, drafts, selection, active session, and user data across resize, DPI/profile change, cancellation, and async completion.

## Evidence language

Report commands, exit status, test counts, affected files, and observed platform/profile. Separate `PASS`, `FAIL`, and `NOT_RUN`. Do not call a render test a screen-reader test, a macOS screenshot a Windows check, or compilation a UX validation.

When a platform run is unavailable, state the exact missing environment and the manual scenario still required. Do not add a bypass, weaken public API boundaries, or edit snapshots directly to make acceptance appear complete.

## Research provenance

The local skill text is original and uses behavior themes from primary Apple, Microsoft, and JetBrains documentation. Two candidate skills were reviewed only for applicability: `yetone/native-feel-skill` and `Meet-Miyani/compose-skill`, both MIT at the reviewed upstream repositories. No text, code, reference tree, install script, WebView architecture, Material mandate, MVI/Hilt/Navigation requirement, or dependency was copied from them. See `docs/desktop-ui/SOURCES.md` for URLs, license evidence, scope, and limitations.
