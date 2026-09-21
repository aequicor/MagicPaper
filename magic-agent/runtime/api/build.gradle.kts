plugins { id("magicpaper.jvm-compose-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":magic-agent:native-recovery:api"))
            api(project(":magic-agent:workspace:api"))
            api(project(":magic-agent:computer:api"))
            api(project(":magic-agent:browser:api"))
            api(project(":magic-chat:api"))
            api(project(":magic-common:transcript"))
            api(project(":designSystem"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(project(":core:model"))
            api(project(":magic-agent:organism:api"))
            api(project(":magic-agent:planning:api"))
            api(project(":core:storage:api"))
            api(project(":feature:skills:api"))
            api(project(":feature:plugins:api"))
            api(project(":feature:settings:api"))
            api(project(":magic-common:tools:api"))
            api(project(":magic-common:questionnaire:api"))
            api(project(":core:ai:api"))
            api(project(":core:platform"))
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.compose.foundation)
            api(libs.decompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.markdownRenderer.m3)
        }
    }
}
