plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Desktop-only feature: no Android, JS or Wasm target, so its code cannot reach
// those artifacts even by accident. `jvmTest` still exists, which checkMigrationJvm requires.
kotlin {
    jvm()
}
