import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    js {
        browser()
        binaries.executable()
        compilerOptions.freeCompilerArgs.add("-Xpartial-linkage-loglevel=ERROR")
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
        compilerOptions.freeCompilerArgs.add("-Xpartial-linkage-loglevel=ERROR")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":app"))
            implementation(project(":designSystem"))

            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.browser)
        }
    }
}
