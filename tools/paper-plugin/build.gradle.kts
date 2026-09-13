plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":designSystem"))
    implementation(project(":core:logging"))
    api("io.aequicor.visualization:editor")
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(compose.desktop.currentOs)
    testImplementation(libs.kotlin.testJunit)
    testImplementation("io.aequicor.visualization:backend-compose")
}
