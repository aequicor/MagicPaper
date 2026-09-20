plugins { id("magicpaper.compose-library") }

// Session presentation shared by an ordinary chat and a project session: transcript rows,
// history actions, media views and the naming rules behind them. Not an api/impl pair —
// both feature implementations consume it, and a feature implementation may not depend on
// another implementation module.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:logging"))
            api(project(":core:model"))
            api(project(":feature:session:api"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:ai:api"))
            implementation(project(":feature:tools:api"))
            implementation(project(":feature:settings:api"))
            implementation(project(":feature:skills:api"))
            implementation(project(":designSystem"))
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
