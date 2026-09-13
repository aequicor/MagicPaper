plugins { id("magicpaper.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:logging"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
