plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":tools:paper-plugin"))
    implementation(project(":designSystem"))
    implementation(project(":core:logging"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.serializationJson)
    implementation("io.aequicor.visualization:backend-compose")
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.kotlinx.coroutinesSwing)
    testImplementation(libs.kotlin.testJunit)
}

compose.desktop {
    application {
        mainClass = "io.aequicor.magicpaper.tools.editor.MainKt"
        nativeDistributions {
            packageName = "PaperEditor"
            packageVersion = "1.0.0"
            macOS { bundleID = "io.aequicor.magicpaper.paper-editor" }
        }
        providers.gradleProperty("paperEditorDataDir").orNull?.let {
            jvmArgs += "-Dmission.visualization.dataDir=$it"
        }
    }
}
