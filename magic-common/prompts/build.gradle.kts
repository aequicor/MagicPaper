plugins { id("magicpaper.kmp-library") }

// Prompt assembly is pure: execution, platform permissions and resource paths stay with callers.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(project(":magic-common:tools:api"))
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
