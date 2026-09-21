plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Native contracts and adapters cannot be selected by Android, JS or Wasm consumers.
// Keep KMP source-set names so the migration JVM check also discovers these modules.
kotlin {
    jvm()
}
