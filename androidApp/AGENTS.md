# Android host

Start in `src/main/kotlin/io/aequicor/magicpaper/MainActivity.kt` and the manifest.
Application composition and navigation belong to `:app`; this module owns the Android host.

- Retain the runtime and root across Activity recreation; inject application context,
  not an Activity. Construct owners outside `setContent` recomposition.
- Handle both launch links and `onNewIntent`. Recreating the Activity must not apply
  the same launch link or start restored background work twice.
- Let the root coordinate Back so a dialog closes before the history changes.
  Keep application shutdown distinct from the user stopping an individual task.
- Verify APK assembly and affected host behavior. A `NO-SOURCE` unit task alone
  does not prove recreation or link handling.

Platform compilation: `./gradlew compileMigrationTargets`.
