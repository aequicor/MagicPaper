# macOS and Windows behavior

Read this reference for interaction, navigation, keyboard, focus, windows, menus, dialogs, density, accessibility, or platform adaptation.

## Shared product identity

Retain the parchment surfaces, ink-like hierarchy, MagicPaper typography, product wording, and calm motion. Platform adaptation changes conventions and behavior; it does not replace the product with a generic Material, AppKit, or WinUI clone.

## macOS profile

- Prefer system window controls, menu bar commands, resizable/full-screen behavior, precise pointer input, and comfortable desktop information density.
- Use Command-based standard shortcuts where applicable; Settings uses Command-comma, close uses Command-W, and quit remains an application command.
- Make Full Keyboard Access practical: stable order, visible focus, standard activation, and no pointer-only actions.
- Treat a sheet-like flow as behavior that must be verified; a Compose dialog is not automatically a native AppKit sheet.

Primary sources: [Designing for macOS](https://developer.apple.com/design/human-interface-guidelines/designing-for-macos), [Keyboards](https://developer.apple.com/design/human-interface-guidelines/keyboards), [Menus](https://developer.apple.com/design/human-interface-guidelines/menus), [Alerts](https://developer.apple.com/design/human-interface-guidelines/alerts), [Windows](https://developer.apple.com/design/human-interface-guidelines/windows), and [Full Keyboard Access](https://support.apple.com/guide/mac-help/use-full-keyboard-access-mchlc06d1059/mac).

## Windows profile

- Maintain predictable Tab order; use arrow keys inside composite controls; show keyboard focus; expose standard activation and accelerators.
- A modal blocks its owner, offers a safe close/cancel path, and keeps contextual validation near the invalid input. Confirm destructive actions explicitly.
- Custom title bars preserve caption-button insets, drag regions, resize, non-client behavior, inactive state, and Snap. Use system fallback when JBR integration is unavailable.
- Verify text scaling separately from display scaling and retain controls/actions at 100%, 125%, 150%, and 200%.

Primary sources: [Keyboard interactions](https://learn.microsoft.com/en-us/windows/apps/develop/input/keyboard-interactions), [Dialogs](https://learn.microsoft.com/en-us/windows/apps/develop/ui/controls/dialogs-and-flyouts/dialogs), [Title bar customization](https://learn.microsoft.com/en-us/windows/apps/develop/title-bar), and [Make text and apps bigger](https://support.microsoft.com/en-us/accessibility/windows/make-text-and-apps-bigger).

## Cross-platform acceptance

Exercise normal, maximized/full-screen, inactive, narrow, long-content, keyboard-only, pointer, and cancellation paths. Test macOS at 1x/2x and Windows at 100/125/150/200% when those environments are available. Check VoiceOver on macOS and NVDA/Java Access Bridge on Windows; automated semantics tests supplement but do not replace these runs.

Compose references: [Desktop accessibility](https://kotlinlang.org/docs/multiplatform/compose-desktop-accessibility.html) and [top-level windows](https://kotlinlang.org/docs/multiplatform/compose-desktop-top-level-windows-management.html).
