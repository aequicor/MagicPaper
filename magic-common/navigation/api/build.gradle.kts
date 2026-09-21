plugins { id("magicpaper.kmp-library") }

// Routes, the visit journal and the navigation machine. The shell that executes its effects is
// the composition root: Decompose routing, Compose presentation and the browser history bridge
// stay there, so these rules stay testable without a component tree.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:state-machine:api"))
            api(libs.kotlinx.serializationJson)
        }
        commonTest { kotlin.srcDir(rootProject.file("testSupport/statemachine")) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
