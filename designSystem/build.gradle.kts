import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/**
 * The visual boundary for every MagicPaper client.  In particular, this
 * project deliberately has no dependency on :app: it is safe for features
 * and host applications to depend on it without creating a module cycle.
 */
kotlin {
    jvm()
    js { browser() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }
    android {
        namespace = "io.aequicor.magicpaper.designsystem"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        val skikoMain by creating { dependsOn(commonMain.get()) }
        jvmMain { dependsOn(skikoMain) }
        webMain { dependsOn(skikoMain) }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            // Material is an implementation detail of Paper components only.
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.markdownRenderer.m3)
            implementation(libs.markdownRenderer.code)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        jvmMain.dependencies {
            implementation(libs.compose.media.player)
            compileOnly(libs.jbr.api)
            implementation(libs.oshi.core)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}

compose {
    resources {
        packageOfResClass = "io.aequicor.magicpaper.designsystem.resources"
        generateResClass = always
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("magicpaper.media.native", providers.gradleProperty("magicpaper.media.native").getOrElse("false"))
}
