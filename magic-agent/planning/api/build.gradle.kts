plugins { id("magicpaper.jvm-library") }
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":magic-agent:native-recovery:api"))
            api(project(":core:model"))
            api(project(":core:storage:api"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
