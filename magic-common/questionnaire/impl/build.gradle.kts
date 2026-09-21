plugins { id("magicpaper.kmp-library") }
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":magic-common:questionnaire:api"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:logging"))
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
