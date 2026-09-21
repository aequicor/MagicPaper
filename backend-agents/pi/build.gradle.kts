plugins { id("magicpaper.jvm-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":backend-agents:api"))
            implementation(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/nativeLifecycle")) }
        jvmTest.dependencies { implementation(project(":backend-agents:lifecycle:impl")) }
    }
}

val nodeProtocolTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Check native provider and planning protocols with local fixtures."
    commandLine(providers.gradleProperty("magicpaper.node").getOrElse("node"), "--test",
        "src/jvmTest/resources/coding/planning-tools.test.mjs", "src/jvmTest/resources/coding/provider-bridge.test.mjs",
        "src/jvmTest/resources/coding/shell-timeout.test.mjs")
    workingDir(projectDir)
    inputs.dir("src/jvmMain/resources/coding")
    inputs.dir("src/jvmTest/resources/coding")
}
tasks.named("check") { dependsOn(nodeProtocolTest) }
