plugins { id("magicpaper.jvm-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":magic-agent:workspace:api"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:logging"))
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(project(":core:storage:api"))
        }
    }
}
