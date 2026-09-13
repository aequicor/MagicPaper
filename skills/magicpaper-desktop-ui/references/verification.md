# Verification and evidence

Read this reference before declaring a UI task complete.

## Select checks by risk

- Always run the deterministic desktop UI contract checks and `git diff --check`.
- Run focused DS/component tests and tests named by `docs/desktop-ui/CONTRACT.md` for behavior touched by the change.
- Compile every affected target. A successful JVM build does not prove Android, JS, or Wasm compatibility.
- For dialogs, windows, focus, keyboard, scaling, accessibility, IME, drag/resize, Snap, or menus, capture separate platform evidence where available.
- Preserve scroll anchors, drafts, selection, active session, and user data across resize, DPI/profile change, cancellation, and async completion.
- Use named Compose previews and the applicable state matrix in
  [compose-previews.md](compose-previews.md). Inspect actual output and exercise
  supported interactions; declarations without a render are not acceptance evidence.

## Evidence language

Report commands, exit status, test counts, affected files, and observed platform/profile. Separate `PASS`, `FAIL`, and `NOT_RUN`. Do not call a render test a screen-reader test, a macOS screenshot a Windows check, or compilation a UX validation.

When a platform run is unavailable, state the exact missing environment and the manual scenario still required. Do not add a bypass, weaken public API boundaries, or edit snapshots directly to make acceptance appear complete.

## Direct the reviewer to the changed UI

For visual changes, render and inspect the implemented screen at representative
size and at the relevant narrow/large-text state. Compare its hierarchy,
proportions, guidelines, spacing, contrast and state distinctions to the intended
composition. Fix visible problems before handing it off. Verify behavior with
actual controls as well as the render; a mockup is not implementation evidence.

Provide a compact acceptance map with these fields for each changed area:

| Field | What the reviewer needs |
| --- | --- |
| Entry | App route and shortest UI steps; isolated fixture or existing object needed to reach the state |
| Preview | Preview file, symbol/group and fixture case; list states inspected and how they were rendered |
| Environment | OS/browser, window size, display scale and text scale; input method when relevant |
| Evidence | Link to the actual screenshot/render and the state it shows; preserve an unmodified render |
| Locate | Region/control name or numbered callout tied to that image; file/component link when useful |
| Check | Specific observable expectation for geometry/contrast and a short action/result sequence |
| Efficiency | User goal and starting context; before/after action path and any necessary confirmations retained |
| Responsiveness | For affected flows, input response and scrolling/animation frame behavior under stated data/load; measured evidence or NOT_RUN |
| Coverage | PASS/FAIL/NOT_RUN for the applicable state or platform; remaining uncertainty |

Example checks: a field, section heading and content list share the same leading
guideline; the primary action remains visible with large text; Tab reaches it;
Escape closes only the current dialog and restores focus. For proportion changes,
identify the two regions and the intended relationship, including its narrow-window
fallback. For color changes, record the relevant color pair/state and contrast.

Keep this map focused on the changed behavior. Use stable labels/semantics and
screen landmarks for navigation; coordinates alone go stale after resize. Callouts
belong in review evidence, not in production UI explanations. Do not make the
reviewer search the entire application or infer the affected region from a file diff.

## Research provenance

The local skill text is original and uses behavior themes from primary Apple, Microsoft, and JetBrains documentation. The visual-design reference also links primary Android/Material and W3C guidance; golden-section composition is a project preference. Two candidate skills were reviewed only for applicability: `yetone/native-feel-skill` and `Meet-Miyani/compose-skill`, both MIT at the reviewed upstream repositories. No text, code, reference tree, install script, WebView architecture, Material mandate, MVI/Hilt/Navigation requirement, or dependency was copied from them. See `docs/desktop-ui/SOURCES.md` for URLs, license evidence, scope, and limitations.
