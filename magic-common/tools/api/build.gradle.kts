plugins { id("magicpaper.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":magic-common:questionnaire:api"))
            api(project(":magic-common:media:api"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.ktor.clientCore)
            api(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
