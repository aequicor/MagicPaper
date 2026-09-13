# Gradle conventions

Use `src/main/kotlin/magicpaper.kmp-library.gradle.kts` and
`magicpaper.compose-library.gradle.kts` for shared target/plugin configuration.
Versions come from the root `gradle/libs.versions.toml`.

Keep project dependency edges explicit in each module's `build.gradle.kts` so the
architecture verifier sees them. Do not hide feature coupling in a convention.
Preserve JVM, Android, JS and Wasm targets. Verify configuration and affected target
compilation/linking when conventions change; do not upgrade the toolchain as an
unrelated part of a module move.
