plugins { id("magicpaper.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":feature:custom-tools:api"))
            implementation(project(":core:model"))
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
