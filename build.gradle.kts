plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}
// Link one DOM implementation on JS; core:platform supplies the Compose SAM ABI.
// Compile classpaths and Wasm stay intact. See docs/WEB_ABI.md.
subprojects {
    configurations.matching { it.name.startsWith("js") && it.name.endsWith("RuntimeClasspath") }.configureEach {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-dom-api-compat")
    }
}

// Both verifiers are tooling, not documentation, so they live in tools/verify/. They gate `check`,
// not compilation: a missing interpreter or script must not stop an ordinary build or app run.
val verifyDesignSystem by tasks.registering(Exec::class) {
    group = "verification"
    description = "Reject Material and raw interactive primitives outside the Paper design system."
    commandLine("python3", rootProject.file("tools/verify/verify-design-system.py"), "--self-test")
    workingDir(rootDir)
}

val verifyModuleArchitecture by tasks.registering(Exec::class) {
    group = "verification"
    description = "Check API/implementation dependency boundaries across all nested modules."
    commandLine("python3", rootProject.file("tools/verify/verify-module-architecture.py"), "--self-test")
    workingDir(rootDir)
}
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyDesignSystem, verifyModuleArchitecture) }
}

val checkMigrationJvm by tasks.registering {
    group = "verification"
    description = "Run JVM tests for every application, infrastructure and feature module."
    dependsOn(verifyDesignSystem, verifyModuleArchitecture)
    dependsOn(subprojects.filter { it.path !in setOf(":androidApp", ":webApp", ":desktopApp") && it.buildFile.isFile }.map { "${it.path}:jvmTest" })
    dependsOn(":desktopApp:test", ":backend-agents:pi:nodeProtocolTest")
}
val compileMigrationTargets by tasks.registering {
    group = "verification"
    description = "Build Android and link Desktop, JS and Wasm application targets."
    dependsOn(":desktopApp:compileKotlin", ":androidApp:assembleDebug",
        ":webApp:jsBrowserDevelopmentExecutableDistribution", ":webApp:wasmJsBrowserDevelopmentExecutableDistribution",
        ":app:compileAndroidMain")
}
tasks.register("verifyMigration") {
    group = "verification"
    description = "Validate the feature migration, Paper boundary and platform compilation."
    dependsOn(checkMigrationJvm, compileMigrationTargets, ":app:testAndroidHostTest", ":androidApp:testDebugUnitTest")
}
