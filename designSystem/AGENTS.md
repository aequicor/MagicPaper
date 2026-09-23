# Paper API

Read `skills/magicpaper-desktop-ui/SKILL.md` for interface work; the palette lives
in `PaperTheme.kt`. `src/commonMain/.../designsystem` owns public
Paper controls; moved generic rendering/window helpers also live in this module.

- New reusable visual/interactive behavior belongs here. Publish a narrow Paper
  contract; keep Material types and implementation details inside the module.
- Give a control consistent semantics, focus, keyboard, hover/pressed/disabled
  behavior and hit area. Center button icon+label as one group by default.
- Modal lifecycle hooks report presentation identity; they must not depend on a
  feature service, Koin or Decompose. Domain decisions stay in the owning feature.
- Test relevant semantic actions and geometry at narrow widths and large text;
  inspect produced renders when layout changes. Screenshots alone do not prove
  keyboard behavior or native Windows/macOS integration.
- Design related elements around shared alignment guidelines, Paper spacing and
  balanced proportions. Measure contrast in the actual component states and keep
  controls recognizable through the target platform's interaction conventions.
- Follow the UI skill's visual-design and verification references: make a substantial
  composition visible first, then provide the route, render and specific regions/
  actions the reviewer should inspect in the implemented UI.
- Add/update named Compose previews for new or meaningfully changed Paper controls,
  using real composables, isolated state and applicable state/size/text-scale cases.
  Inspect preview output and pair it with behavior checks; simulated platform policy
  previews do not replace native platform evidence.

Update the affected surface binding when public UI ownership changes. Run the
Paper verifier and relevant design-system tests, then the affected consumers.
