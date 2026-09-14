plugins { id("magicpaper.compose-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":designSystem"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(project(":core:model"))
            api(project(":core:storage:api"))
            api(project(":feature:skills:api"))
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
