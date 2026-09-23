# Web host

Start in `src/webMain/kotlin/io/aequicor/magicpaper/main.kt`, `build.gradle.kts`
and `webpack.config.d/`. Application history behavior lives in `app/src/commonMain/kotlin/io/aequicor/magicpaper/navigation`.

- Keep one bridge between browser history and the root journal; do not attach a
  parallel `withWebHistory`. Process popstate sequentially without writing it back
  as a new user navigation.
- Tabs own separate journals/segments. Restore only app-owned history; preserve
  the saved Forward branch. Direct URLs require the index.html fallback.
- Construct runtime/root outside recomposition. Restore precedes a new external
  link, and onboarding can defer that transition.
- Check JS and Wasm independently through executable linking. Browser execution
  is needed for History API and IndexedDB behavior, beyond compiler success.

Platform compilation: `./gradlew compileMigrationTargets`.
