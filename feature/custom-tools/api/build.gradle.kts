plugins { id("magicpaper.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
