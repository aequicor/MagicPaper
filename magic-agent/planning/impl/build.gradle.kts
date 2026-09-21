plugins { id("magicpaper.jvm-library") }
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":magic-agent:planning:api"))
            implementation(project(":core:logging"))
            implementation(project(":magic-common:tools:api"))
        }
        commonTest.dependencies {
            implementation(project(":core:storage:impl"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
