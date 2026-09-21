plugins { id("magicpaper.kmp-library") }

// Turns a declared state space into a table, a diagram and a page. It is deliberately not the
// home of the journal adapter: that has to be reachable from the owners' own impl modules, which
// may not depend on any other impl, so it belongs beside EventJournal in :core:storage:api.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:state-machine:api"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
