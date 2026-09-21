plugins { id("magicpaper.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":magic-common:request-pins:api"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:ai:api"))
            implementation(project(":core:logging"))
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(project(":core:storage:impl"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
