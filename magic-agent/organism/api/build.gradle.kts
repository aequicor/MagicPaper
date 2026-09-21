plugins { id("magicpaper.jvm-library") }
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:state-machine:api"))
            implementation(project(":magic-common:tools:api"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
        }
        commonTest { kotlin.srcDir(rootProject.file("testSupport/statemachine")) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
