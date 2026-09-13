---
name: magicpaper-desktop-ui
description: Implement or review MagicPaper Compose Multiplatform desktop UI for macOS and Windows. Use for screens, dialogs, navigation, window chrome, interactions, accessibility, or components in this repository; route all visual and interactive UI through the public Paper design-system API. Do not use for backend-only work or unrelated generic Compose applications.
license: MIT
metadata:
  version: "1.1.0"
  package-id: "magicpaper.desktop-ui"
---

# MagicPaper desktop UI

Preserve MagicPaper's warm paper identity while making behavior, density, navigation, focus, and keyboard interaction familiar on macOS and Windows. Keep Android and web compiling unless the task explicitly expands their design scope.

## Before editing

1. Read `docs/desktop-ui/CONTRACT.md` and the relevant rows in `docs/desktop-ui/SURFACE-MAP.md`. Treat `surface-bindings.json` as the migration assignment, not as generated API.
2. Inspect the current public API of `:designSystem` and its call sites. If the module does not exist yet, create it according to the contract before migrating feature UI.
3. Read [design-system.md](references/design-system.md). For platform behavior, window chrome, menus, keyboard, focus, dialogs, or accessibility, also read [platform-behavior.md](references/platform-behavior.md). For completion evidence, read [verification.md](references/verification.md).
4. Record pre-existing working-tree changes and preserve them. Do not treat unrelated recovery or parallel-session edits as yours.

## Visual design and recognition

For screens, layout, typography, color or visual hierarchy, read
[visual-design.md](references/visual-design.md). Form a visible composition with
shared alignment guidelines and intentional proportions before a substantial
implementation. Use golden-section relationships where they fit the content;
readability, usable controls and platform behavior take priority over a fixed ratio.
Apply modern Material Design principles through Paper while preserving familiar
OS/browser interactions. The user should recognize how to act from the controls
and their behavior, with concise labels rather than an explanatory walkthrough.

Actively use [Compose previews](references/compose-previews.md) to design and accept
new or meaningfully changed components. Maintain named, isolated previews of the
real composable across applicable states and constraints; inspect their output.
Include the preview entry and state matrix in the review handoff. Interactive
preview and render/semantics tests complement actual platform checks.

Minimize the full path to the user's goal and keep interactions responsive and
smooth. For action flows, scrolling, streaming or animation changes, use
[interaction-quality.md](references/interaction-quality.md) to check step count,
input response, frame pacing and motion without removing necessary safeguards.

## Non-negotiable boundary

- Feature and app modules use only the public `Paper*` design-system API for visual or interactive components, semantic tokens, focus indication, control sizing, menus, dialogs, Markdown rendering, and window UI.
- Direct Material 2/3 or Material-backed Markdown imports are allowed only inside `:designSystem`. Do not leak `ColorScheme`, `Typography`, `ButtonColors`, or other Material implementation types through its public API.
- Foundation/layout primitives may remain outside the design system for feature composition. If a feature invents a reusable interactive primitive, raw visual value, focus treatment, or control state, extend the design system first.
- Never bypass the boundary with aliases, fully qualified Material calls, wildcard imports, copied private DS implementation, or feature-local lookalike components.
- Use public API from another module. If existing public API cannot express the required behavior, extend it narrowly in `:designSystem`, document semantics and platform variation, test it, then consume the new public API. Do not make internals public merely to satisfy a call site.

## Brand and interaction

- Read `docs/desktop-ui/BRANDBOOK.md` for the current palette; the earlier contract palette is historical baseline.
- Pastel sand, lilac, grey, white, ash green and coral are surfaces. Black-burgundy ink and accessible dark accents carry text.
- Use `paperClickable` or a Paper control for actions. The same shape clips the full hover/pressed fill and focus outline; preserve selected state when the pointer leaves. Do not add ripple, pointer-only handlers or feature-local hover drawing.
- By default, center button content horizontally and vertically within the hit area; center an icon and label together as one group. Override this only when an explicit component or platform guideline requires different alignment. Align forms and lists to shared leading guides, and use Paper spacing/density rather than arbitrary gaps.
- UI contains names, actions, brief validation and necessary consequences. Keep descriptions of algorithms, atomic writes, adapters and implementation mechanics in code.

## Implementation decisions

- Preserve product vocabulary, content, user data, state restoration, and the established parchment palette and typography. Native feel means platform-appropriate behavior, not replacing the brand with AppKit or WinUI styling.
- Keep shared state and domain logic platform-neutral. Put macOS/Windows differences behind a platform policy or adapter selected once, with safe common fallbacks for Android, JS, and Wasm.
- Follow established project state patterns. Do not impose WebView, React, Node, Rust, MVI, Hilt, Navigation 3, or another architecture as a prerequisite.
- Prefer OS-owned window controls and standard commands. Never draw imitations of system caption buttons when the OS owns them.
- Define interaction by semantics: primary/destructive intent, focus order, default/cancel action, selection, disabled/busy/error state, and restoration behavior. Avoid platform checks scattered through feature composables.
- Make actions visibly available and preserve keyboard accessibility: visible focus, predictable Tab order, arrow-key behavior within composite controls, standard activation, and Escape cancellation where safe. Do not add keyboard shortcuts as a substitute for a short, understandable action path or require memorized combinations; preserve OS/browser-owned input behavior.
- Make asynchronous completion identity-safe: an old task must not close or mutate a newer dialog, screen, or selection.
- For narrow windows, scaling, inactive windows, and long/localized content, adapt layout without losing actions or data. Do not solve overflow by silently clipping essential controls.

## Completion

Implement the smallest coherent public API and migrate every in-scope call site from its surface-map row. Run the focused tests plus the architectural boundary check and relevant target compilations. Report what was actually exercised on macOS and Windows separately; never infer Windows native behavior from a macOS screenshot or JVM unit test.

Inspect an actual render for visual changes. Give the reviewer a focused acceptance
route to the affected screen/state and identify the regions and interactions to
check, following [verification.md](references/verification.md). A wireframe explains
the proposal; evidence of completion comes from the implemented UI.

If the task requires a new design-system capability, finish the DS API, behavior, and tests before calling the feature migration complete. If platform evidence is unavailable, leave it explicitly `NOT_RUN` with a concrete follow-up rather than weakening the requirement.
