import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    android {
       namespace = "io.aequicor.magicpaper.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()

       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.ktor.clientCio)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.clientCore)
            implementation(libs.markdownRenderer.m3)
            implementation(libs.markdownRenderer.code)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
        }
        jvmMain.dependencies {
            implementation(libs.oshi.core)
            implementation(libs.jbr.api)
            implementation(libs.ktor.clientCio)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
        webMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.ktor.clientJs)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

// Фиксируем пакет сгенерированного класса Res: группа модуля не задана,
// без явного пакета импорты ресурсов были бы нестабильны.
compose {
    resources {
        packageOfResClass = "io.aequicor.magicpaper.resources"
        generateResClass = always
    }
}

// Интеграционные тесты включаются флагами magicpaper.pi.it / magicpaper.codex.it.
tasks.withType<Test>().configureEach {
    systemProperty("magicpaper.pi.it", providers.gradleProperty("magicpaper.pi.it").getOrElse("false"))
    systemProperty("magicpaper.research.native", providers.gradleProperty("magicpaper.research.native").getOrElse("false"))
    systemProperty("magicpaper.codex.it", providers.gradleProperty("magicpaper.codex.it").getOrElse("false"))
}
