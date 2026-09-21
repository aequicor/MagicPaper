plugins { id("magicpaper.kmp-library") }

// Stateless research rules and bounded source reading are shared by chat and agents.
// This module has no session owner, native process, UI, or durable lifecycle to model.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(project(":core:logging"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(libs.ktor.clientCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
