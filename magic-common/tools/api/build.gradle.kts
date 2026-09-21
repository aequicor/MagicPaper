plugins { id("magicpaper.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:state-machine:api"))
            api(project(":magic-common:questionnaire:api"))
            api(project(":magic-common:media:api"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.ktor.clientCore)
            api(libs.kotlinx.serializationJson)
        }
        commonTest { kotlin.srcDir(rootProject.file("testSupport/statemachine")) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
