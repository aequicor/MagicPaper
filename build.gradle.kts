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
val verifyDesignSystem by tasks.registering(Exec::class) {
    group = "verification"
    description = "Reject Material and raw interactive primitives outside the Paper design system."
    commandLine("python3", rootProject.file("docs/desktop-ui/verify-design-system.py"), "--self-test")
    workingDir(rootDir)
}
subprojects {
    if (name != "designSystem") {
        tasks.configureEach {
            if (name == "check" || name.startsWith("compile") && name.contains("Kotlin")) {
                dependsOn(rootProject.tasks.named("verifyDesignSystem"))
            }
        }
    }
}
