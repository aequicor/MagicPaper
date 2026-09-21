plugins { id("magicpaper.jvm-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}

val screenshotContextTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Check screenshot context projection with local fixtures."
    commandLine(providers.gradleProperty("magicpaper.node").getOrElse("node"), "--test", "src/jvmTest/resources/computer/screenshot-context.test.mjs")
    workingDir(projectDir)
    inputs.file("src/jvmTest/resources/computer/screenshot-context.test.mjs")
    inputs.file("src/jvmMain/resources/computer/screenshot-context.js")
}
tasks.named("check") { dependsOn(screenshotContextTest) }
