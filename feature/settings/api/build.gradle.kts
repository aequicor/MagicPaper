plugins { id("magicpaper.compose-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(project(":core:model"))
            api(project(":core:state-machine:api"))
            api(project(":core:ai:api"))
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.decompose)
        }
        commonTest { kotlin.srcDir(rootProject.file("testSupport/statemachine")) }
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
