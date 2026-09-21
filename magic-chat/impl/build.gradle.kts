plugins { id("magicpaper.compose-library") }

kotlin {
    sourceSets {
        commonTest { kotlin.srcDir(rootProject.file("testSupport/provider")) }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/rendering")) }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/filesystems")) }
        commonMain.dependencies {
            api(project(":magic-chat:api"))
            implementation(project(":magic-common:research"))
            implementation(project(":magic-common:prompts"))
            implementation(project(":core:logging"))
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
            implementation(project(":magic-common:transcript"))
            implementation(project(":magic-common:tools:api"))
            implementation(project(":feature:settings:api"))
            implementation(project(":feature:docs:api"))
            implementation(project(":feature:plugins:api"))
            implementation(project(":feature:skills:api"))
            implementation(libs.ktor.clientCore)
        }
        commonTest.dependencies {
            implementation(project(":magic-common:tools:impl"))
            implementation(project(":magic-common:questionnaire:impl"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:storage:impl"))
            implementation(project(":core:ai:impl"))
            implementation(project(":feature:settings:impl"))
            implementation(project(":feature:skills:impl"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.markdownRenderer.m3)
        }
    }
}
