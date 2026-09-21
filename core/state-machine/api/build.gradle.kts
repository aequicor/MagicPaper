plugins { id("magicpaper.kmp-library") }

// A foundational leaf: the machine contract, the declared state space and the journaled
// runtime port. It carries no project dependency at all, because :core:model and
// :backend-agents:api own machines and may depend only on modules of that kind.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
