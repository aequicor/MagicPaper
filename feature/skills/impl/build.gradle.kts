plugins { id("magicpaper.compose-library") }

kotlin {
    sourceSets {
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/provider")) }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/rendering")) }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/native")) }
        commonMain.dependencies {
            implementation(project(":core:logging"))
            implementation(libs.ktor.utils)
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(project(":core:model"))
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.decompose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.uiToolingPreview)
            implementation(project(":designSystem"))
            implementation(project(":core:platform"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:ai:api"))
            implementation(project(":magic-chat:api"))
            implementation(project(":feature:settings:api"))
            implementation(project(":feature:docs:api"))
            implementation(project(":feature:plugins:api"))
            implementation(project(":feature:skills:api"))
        }
        commonTest.dependencies {
            implementation(project(":core:storage:api"))
            implementation(project(":core:ai:impl"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmMain.dependencies { implementation(libs.oshi.core) }
        jvmTest.dependencies {
            implementation(project(":magic-agent:computer:api"))
            implementation(project(":magic-agent:browser:api"))
            implementation(project(":magic-common:tools:impl"))
            implementation(project(":magic-common:questionnaire:impl"))
            implementation(project(":core:storage:impl"))
            implementation(project(":magic-chat:impl"))
            implementation(project(":feature:settings:impl"))
            // Desktop coding runtimes the skill wiring tests assemble; only :app links coding:impl.
            implementation(project(":magic-agent:runtime:impl"))
            implementation(project(":magic-agent:planning:impl"))
            // DefaultChatPresentation for the shared render theme; session:impl keeps transcript internal.
            implementation(project(":magic-common:transcript"))
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.markdownRenderer.m3)
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("magicpaper.pi.it", providers.gradleProperty("magicpaper.pi.it").getOrElse("false"))
    systemProperty("magicpaper.codex.it", providers.gradleProperty("magicpaper.codex.it").getOrElse("false"))
    systemProperty("magicpaper.research.native", providers.gradleProperty("magicpaper.research.native").getOrElse("false"))
}
