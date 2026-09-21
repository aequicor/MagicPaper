plugins { id("magicpaper.jvm-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":magic-agent:checks:api"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:logging"))
            implementation(project(":core:platform"))
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
        }
        jvmMain.dependencies { implementation(libs.oshi.core) }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(project(":core:storage:api"))
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("magicpaper.research.native", providers.gradleProperty("magicpaper.research.native").getOrElse("false"))
}
