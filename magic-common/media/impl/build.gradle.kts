plugins { id("magicpaper.kmp-library") }

kotlin {
    sourceSets {
        commonTest { kotlin.srcDir(rootProject.file("testSupport/media")) }
        commonMain.dependencies {
            api(project(":magic-common:media:api"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:ai:api"))
            implementation(project(":core:logging"))
            implementation(project(":magic-common:tools:api"))
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(project(":feature:settings:api"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
