plugins { id("magicpaper.jvm-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:state-machine:api"))
            api(libs.kotlinx.serializationJson)
            api(libs.kotlinx.coroutinesCore)
        }
        commonTest { kotlin.srcDir(rootProject.file("testSupport/statemachine")) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
