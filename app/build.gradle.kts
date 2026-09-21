plugins { id("magicpaper.compose-library") }

// Composition root for all hosts. Feature implementations are assembled only here.
kotlin {
    android {
        androidResources { enable = true }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonTest { kotlin.srcDir(rootProject.file("testSupport/provider")) }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/native")) }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/workspace")) }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/planning")) }
        commonMain.dependencies {
            implementation(project(":magic-common:media:impl"))
            implementation(project(":magic-common:request-pins:impl"))
            implementation(project(":magic-common:research"))
            implementation(project(":magic-common:questionnaire:impl"))
            api(project(":core:logging"))
            api(libs.decompose)
            implementation(libs.decompose.compose)
            api(libs.essenty.lifecycle)
            api(libs.koin.core)
            api(project(":core:model"))
            api(project(":core:platform"))
            api(project(":core:storage:api"))
            api(project(":core:ai:api"))
            implementation(project(":core:storage:impl"))
            implementation(project(":core:ai:impl"))
            api(project(":magic-chat:api"))
            implementation(project(":magic-chat:impl"))
            implementation(project(":magic-common:transcript"))
            implementation(project(":magic-common:tools:api"))
            implementation(project(":magic-common:tools:impl"))
            api(project(":feature:settings:api"))
            implementation(project(":feature:settings:impl"))
            api(project(":feature:docs:api"))
            implementation(project(":feature:docs:impl"))
            api(project(":feature:plugins:api"))
            implementation(project(":feature:plugins:impl"))
            api(project(":feature:skills:api"))
            implementation(project(":feature:skills:impl"))
            implementation(project(":designSystem"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.ktor.clientCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmMain.dependencies {
            implementation(project(":magic-agent:checks:impl"))
            implementation(project(":magic-agent:computer:impl"))
            implementation(project(":magic-agent:browser:impl"))
            implementation(project(":magic-agent:workspace:impl"))
            implementation(project(":magic-agent:organism:impl"))
            implementation(project(":magic-agent:planning:impl"))
            api(project(":magic-agent:runtime:api"))
            // Only the desktop host can run an agent. Declared here and nowhere else, so the
            // Android and browser compilations never see coding at all.
            implementation(project(":magic-agent:runtime:impl"))
        }
        jvmTest.dependencies {
            implementation(libs.koin.test)
            implementation(libs.compose.material3)
            implementation(libs.markdownRenderer.m3)
            implementation(compose.desktop.currentOs)
        }
        webMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(libs.kotlinx.coroutinesCore)
        }
    }
}

// Интеграционные тесты включаются флагами magicpaper.pi.it / magicpaper.codex.it.
tasks.withType<Test>().configureEach {
    systemProperty("magicpaper.pi.it", providers.gradleProperty("magicpaper.pi.it").getOrElse("false"))
    systemProperty("magicpaper.research.native", providers.gradleProperty("magicpaper.research.native").getOrElse("false"))
    systemProperty("magicpaper.codex.it", providers.gradleProperty("magicpaper.codex.it").getOrElse("false"))
}
