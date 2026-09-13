plugins { id("magicpaper.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:logging"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(project(":core:model"))
            api(project(":core:ai:api"))
            implementation(project(":core:storage:api"))
            implementation(libs.ktor.clientCore)
        }
        commonTest.dependencies {
            implementation(project(":core:storage:api"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
        }
        jvmMain.dependencies { implementation(libs.ktor.clientCio) }
        androidMain.dependencies { implementation(libs.ktor.clientCio) }
        webMain.dependencies { implementation(libs.ktor.clientJs) }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("magicpaper.pi.it", providers.gradleProperty("magicpaper.pi.it").getOrElse("false"))
    systemProperty("magicpaper.codex.it", providers.gradleProperty("magicpaper.codex.it").getOrElse("false"))
    systemProperty("magicpaper.research.native", providers.gradleProperty("magicpaper.research.native").getOrElse("false"))
}
