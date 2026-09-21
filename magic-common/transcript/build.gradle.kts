plugins { id("magicpaper.compose-library") }

// Session presentation shared by an ordinary chat and a project session: transcript rows,
// history actions and media views. Not an api/impl pair —
// both feature implementations consume it, and a feature implementation may not depend on
// another implementation module.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":magic-common:research"))
            implementation(project(":core:logging"))
            api(project(":core:model"))
            api(project(":core:platform"))
            api(project(":magic-common:media:api"))
            api(project(":magic-common:request-pins:api"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:ai:api"))
            implementation(project(":magic-common:tools:api"))
            api(project(":designSystem"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.decompose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.ktor.clientCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
