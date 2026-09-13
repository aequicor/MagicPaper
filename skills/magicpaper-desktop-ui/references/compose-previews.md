# Compose previews as a development and acceptance tool

Use previews throughout component work, not only after implementation. New or
meaningfully changed visual components/screens need named preview cases of their
real rendering code. Reuse/update existing cases for a small visual adjustment.

## Tooling and ownership

- Prefer `androidx.compose.ui.tooling.preview.Preview` for the project's current
  Compose generation. The catalog already defines `compose-uiToolingPreview` as
  `org.jetbrains.compose.ui:ui-tooling-preview`; check the owner's source-set/tooling
  dependencies before adding setup. Do not copy the deprecated JetBrains annotation
  or upgrade Kotlin/Compose/AGP incidentally to enable a preview.
- Common-code IDE previews use Android tooling; check the IDE and AGP configuration
  actually available. Follow [KMP preview setup](https://kotlinlang.org/docs/multiplatform/compose-previews.html)
  for the selected plugin/source set rather than adding Android-only dependencies
  to every target. Do not invent a Gradle `preview` task.
- Put Paper-component previews with their design-system owner and screen previews
  in the feature implementation. API modules do not acquire concrete feature
  dependencies for preview convenience. Prefer state-in/event-out rendering and
  local fake ports/factories when a component contract needs them.
- Render through `PaperTheme`, required renderer providers and the relevant
  `PaperPlatformPolicy`. Use deterministic sample state, callbacks and resources.
  Previews must not launch production Koin, native sessions, network requests,
  file pickers or writes to user storage; preview lifecycle must release its owners.

## Choose the state matrix

Use named previews and `group` so the reviewer can select one component's gallery.
Combine repeated `@Preview`, reusable multipreview annotations or
`@PreviewParameter` providers when supported and useful. Group realistic cases;
avoid a large Cartesian product of states that cannot occur together.

| Dimension | Cases to consider when applicable |
| --- | --- |
| Data | Typical, empty, long/localized text, large collection, validation error |
| Operation | Idle, loading/busy, recoverable failure, success/result |
| Control | Enabled, disabled, selected/expanded, keyboard focus, hover/pressed |
| Layout | Normal and narrow constraints, enlarged text, scrolling/overflow |
| Platform | Relevant Paper policy, direction/input conventions, supported theme |

Do not invent unsupported states or a new dark theme just to populate a gallery.
Use the project's typography/spacing rather than preview-only styling that hides
layout defects. Set explicit size/locale/font scale where the tool supports them;
otherwise configure the equivalent fixture and record how it was rendered.

## Inspect and exercise

Inspect the gallery together for consistent guidelines, proportions and states,
then focus on the failing case while iterating. Use interactive preview for actions,
expansion, selection and dismissal where supported. Use existing Compose Hot Reload
or a running isolated desktop preview when it helps verify a desktop interaction;
new tooling installation is not a prerequisite to an ordinary UI fix.

IDE capabilities: [Compose preview configuration, groups, data and interaction](https://developer.android.com/develop/ui/compose/tooling/previews).

If IDE preview is unavailable, exercise the same real composable/state fixtures in
a Compose render harness such as the project's `ImageComposeScene` tests and inspect
the generated images. Semantics tests verify focus, activation and state changes
that a static preview cannot establish. Existing examples can be found in
`designSystem/src/jvmTest`, `app/src/jvmTest` and the owning `feature/*/impl/src/jvmTest`
from the repository root.

For acceptance, link the preview file and symbol/group, list the inspected cases,
and attach the real output with focused region checks from
[verification.md](verification.md). Mark unavailable IDE/native checks accurately.
An Android preview using a Windows Paper policy is a simulated profile, not proof
of Windows windowing, keyboard, accessibility or installation behavior.
