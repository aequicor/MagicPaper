# Visual composition and familiar interaction

Read for screen composition, layout, typography, color or visual review. The current
project palette and spacing live in `:designSystem`, starting from `PaperTheme.kt`.
This reference explains how to apply them, not a replacement palette.

## Make the composition visible

For a new screen or substantial redesign, inspect the existing UI and create a
small wireframe, region sketch or runnable preview. Show the primary task, reading
order, primary/secondary actions, navigation, content, status and relevant empty,
loading or error states. For a local adjustment, inspect the current render and
mark the affected relationship; a separate design project is unnecessary.

For Compose components, prefer a small real preview over drawing an imitation of
an existing control. Build its relevant state gallery following
[compose-previews.md](compose-previews.md), then inspect both the component and
its placement within the surrounding screen.

Identify the main alignment guidelines on the sketch: navigation/content boundary,
heading and form leading edge, text baselines, action edge and shared gutters.
Include a realistic window size and a narrow/reflow state. Use representative long
labels and content, not only short placeholder text. Continue within the authorized
task; making a sketch is not an automatic user-approval gate.

## Proportions and guidelines

- Use visual hierarchy and space allocation to express importance. Consider the
  golden ratio, approximately 1:1.618 or a 38.2%/61.8% split, for suitable primary
  and supporting regions. This is the user's composition preference, not a Material
  or accessibility requirement. Explain a meaningful departure when content or
  platform constraints make another proportion work better.
- Bound flexible regions with useful minimum/maximum widths. Respect readable line
  lengths, intrinsic content, touch/pointer targets and the space for primary work.
  Reflow or collapse supporting regions before making controls cramped. Never force
  every sidebar, dialog, button or font size into a golden-ratio formula.
- Related elements share a guideline: headings, field labels/inputs and list content
  start on a common leading edge; row text shares baselines; corresponding actions
  share a trailing edge. Nested groups use intentional, repeated indentation.
- Use the existing Paper 4/8-based spacing rhythm and semantic typography roles.
  Equal relationships use equal gaps; groups have distinguishable separation. Keep
  button icon and label centered as a group while the button aligns with its peers.
- Check the whole screen as well as the component: balanced visual weight, stable
  alignment across states, clear grouping, restrained emphasis and no accidental
  one-off offsets. Do not introduce decorative borders, shadows or animations
  merely to make a screen appear contemporary.

Grid and adaptive-layout foundations: [Android grids and units](https://developer.android.com/design/ui/mobile/guides/layout-and-content/grids-and-units)
and [Material adaptive design guidance](https://developer.android.com/codelabs/adaptive-material-guidance?hl=en).
Adapt these principles to Paper and the target platform's controls and density.

## Contrast and distinguishable states

- Measure foreground/background contrast on the actual composited surface,
  including hover, selected and overlay states. Token names or different hues
  do not prove sufficient contrast. Keep palette changes in design-system tokens.
- Target at least 4.5:1 for normal text and 3:1 for text qualifying as large under
  WCAG. Do not assume a Compose `sp` number by itself proves the large-text category.
  Use 4.5:1 when that classification is uncertain.
- Essential control boundaries, state indicators and meaningful graphics need
  3:1 contrast against the adjacent colors needed to identify them. Decorative
  separators need not become heavy borders; disabled styling still needs to make
  its unavailable state understandable.
- Selection, focus, error and success must not depend on red/green or another hue
  alone. Combine color with shape, icon, outline, position or concise status text,
  and expose state through accessibility semantics. Preserve labels and focus.

Contrast criteria: [WCAG text contrast](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)
and [WCAG non-text contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html).

## Recognizable behavior

Use the target platform's familiar interaction vocabulary: discoverable primary
actions, conventional controls/icons, predictable placement, visible feedback,
consistent navigation and reversible actions where possible. A novel icon-only
action plus a long tooltip is not a substitute for a recognizable control.

Material Design supplies useful hierarchy, component, state and adaptive-layout
principles. Paper owns their implementation and brand expression; platform policy
owns OS/browser conventions. Read [platform-behavior.md](platform-behavior.md) for
keyboard, window, menu, dialog, Back and input differences.

Ask whether a user familiar with that platform can locate the main action, infer
what the control does, recognize the current state and recover from a mistake
without a tutorial. Keep necessary labels, accessible names, validation and
consequences; remove prose that merely explains avoidable interaction confusion.

Use [verification.md](verification.md) to inspect the real result and direct the
reviewer to the specific regions/states that establish acceptance.
