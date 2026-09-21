plugins { id("magicpaper.compose-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:state-machine:api"))
            api(project(":magic-common:media:api"))
            api(project(":magic-common:request-pins:api"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.compose.runtime)
            api(libs.decompose)
        }
        commonTest { kotlin.srcDir(rootProject.file("testSupport/statemachine")) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
