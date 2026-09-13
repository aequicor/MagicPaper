plugins { id("magicpaper.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(project(":core:ai:api"))
            implementation(project(":feature:settings:api"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
