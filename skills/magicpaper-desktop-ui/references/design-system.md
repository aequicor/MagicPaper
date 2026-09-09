# Design-system boundary

Read this reference whenever a task creates, changes, or consumes MagicPaper UI.

## Ownership

`:designSystem` owns semantic visual tokens, typography, icons, interactive controls, focus visuals, component state presentation, overlays, menus, dialogs, Markdown rendering, and platform UI policy. It supports JVM, Android, JS, and Wasm and must not depend on `:shared`, features, ViewModels, repositories, or plugin domain models.

Feature code owns domain state, validation decisions, loading operations, navigation intent, and composition of public Paper components. Basic Compose layout/Foundation APIs are permitted for composition, scrolling containers, and positioning, provided they do not recreate an interactive or branded component.

## Public API rules

- Name exported UI and tokens `Paper*` or place them in an equally explicit public Paper namespace.
- Expose semantic intent rather than renderer types: roles and variants instead of raw Material colors; callbacks and immutable state instead of Material state holders.
- Supply meaningful defaults, stable state, enabled/busy/error semantics, keyboard/focus behavior, and testable accessibility semantics.
- Keep platform differences behind `PaperPlatformPolicy` or focused adapters. A caller requests meaning such as settings, close, default action, destructive confirmation, or compact density; it does not branch on the OS.
- Keep component APIs narrow. Add a capability only when at least one mapped surface needs it; avoid a universal parameter bag.
- Preserve binary/source compatibility when practical. A breaking public API change requires migrating all repository callers in the same task and documenting the reason.

## Extending the API

1. Confirm the need against `docs/desktop-ui/SURFACE-MAP.md` and current call sites.
2. Choose an existing primitive/composite before adding a new one. Do not expose an internal Material escape hatch.
3. Define semantic states and platform variation, including keyboard, pointer, focus, accessibility, localization, scaling, busy/error, and dismissal behavior where relevant.
4. Implement common behavior and the smallest platform adapter. Keep non-desktop targets functional with a compatible fallback.
5. Add focused tests at the DS boundary, then use the public API from the feature. Do not import DS internals.
6. Update the surface map or contract only when scope genuinely changes; do not edit generated verification evidence to hide a missing migration.

## Required boundary checks

Scan every production source set and build file, including desktop entry points and built-in JVM plugins. Reject Material 2/3 and Material Markdown imports outside `:designSystem`, including wildcard, alias, and fully qualified forms. A transitive Material runtime is acceptable only as a private DS implementation detail.
