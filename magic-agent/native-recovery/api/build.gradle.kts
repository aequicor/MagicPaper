plugins { id("magicpaper.jvm-library") }
kotlin { sourceSets { commonMain.dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.serializationJson)
} } }
