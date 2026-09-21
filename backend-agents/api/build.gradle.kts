plugins { id("magicpaper.jvm-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(libs.kotlinx.serializationJson)
            api(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
