plugins { id("magicpaper.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":magic-common:tools:api"))
            implementation(project(":core:model"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:logging"))
            implementation(project(":core:ai:api"))
            implementation(project(":magic-common:questionnaire:api"))
            implementation(project(":magic-common:media:api"))
            implementation(project(":magic-common:research"))
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.ktor.clientCore)
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(project(":magic-common:questionnaire:impl"))
        }
    }
}
