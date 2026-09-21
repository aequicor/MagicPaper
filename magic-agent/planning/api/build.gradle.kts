plugins { id("magicpaper.jvm-library") }
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":magic-agent:native-recovery:api"))
            api(project(":core:model"))
            api(project(":core:state-machine:api"))
            api(project(":core:storage:api"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
        }
        commonTest { kotlin.srcDir(rootProject.file("testSupport/statemachine")) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
