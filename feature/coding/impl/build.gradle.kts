plugins { id("magicpaper.compose-library") }

kotlin {
    sourceSets {
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/rendering")) }
        commonTest { kotlin.srcDir(rootProject.file("testSupport/planning")) }
        commonMain.dependencies {
            implementation(project(":core:logging"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(project(":core:model"))
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.decompose)
            implementation(libs.compose.foundation)
            implementation(project(":designSystem"))
            implementation(project(":core:platform"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:ai:api"))
            implementation(libs.koin.core)
            implementation(project(":feature:chat:api"))
            implementation(project(":feature:coding:api"))
            implementation(project(":feature:settings:api"))
            implementation(project(":feature:docs:api"))
            implementation(project(":feature:plugins:api"))
            implementation(project(":feature:skills:api"))
            implementation(libs.ktor.clientCore)
        }
        commonTest.dependencies {
            implementation(project(":core:storage:api"))
            implementation(project(":core:storage:impl"))
            implementation(project(":core:ai:impl"))
            implementation(project(":feature:settings:impl"))
            implementation(project(":feature:chat:impl"))
            implementation(project(":feature:skills:impl"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
        }
        jvmMain.dependencies {
            implementation(libs.oshi.core)
            implementation(libs.ktor.clientCio)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.markdownRenderer.m3)
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("magicpaper.pi.it", providers.gradleProperty("magicpaper.pi.it").getOrElse("false"))
    systemProperty("magicpaper.codex.it", providers.gradleProperty("magicpaper.codex.it").getOrElse("false"))
    systemProperty("magicpaper.research.native", providers.gradleProperty("magicpaper.research.native").getOrElse("false"))
}

val nodeProtocolTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Check the native provider and planning wire protocols with local fixtures."
    commandLine("node", "--test", "src/jvmTest/resources/coding/planning-tools.test.mjs", "src/jvmTest/resources/coding/provider-bridge.test.mjs", "src/jvmTest/resources/coding/shell-timeout.test.mjs")
    workingDir(projectDir)
    inputs.dir("src/jvmMain/resources/coding")
    inputs.dir("src/jvmTest/resources/coding")
}
tasks.named("check") { dependsOn(nodeProtocolTest) }

// DesktopUiSkillIntegrationTest validates the tracked package outside this subproject.
tasks.named<Test>("jvmTest") {
    inputs.dir(rootProject.layout.projectDirectory.dir("skills/magicpaper-desktop-ui"))
}

tasks.register<JavaExec>("installDesktopUiSkillBinding") {
    group = "verification"
    description = "Import, review, bind, and verify the project-local desktop UI skill repository."
    dependsOn("jvmTestClasses")
    classpath(
        configurations.named("jvmTestRuntimeClasspath"),
        layout.buildDirectory.dir("classes/kotlin/jvm/test"),
        layout.buildDirectory.dir("classes/kotlin/jvm/main"),
        layout.buildDirectory.dir("processedResources/jvm/main"),
    )
    mainClass.set("io.aequicor.magicpaper.data.skills.DesktopUiSkillBindingTool")
    args(
        rootProject.layout.projectDirectory.dir("skills/magicpaper-desktop-ui").asFile.absolutePath,
        rootProject.layout.projectDirectory.dir(".magicpaper/skill-packages").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("docs/desktop-ui/active-binding.json").asFile.absolutePath,
    )
    inputs.dir(rootProject.layout.projectDirectory.dir("skills/magicpaper-desktop-ui"))
    outputs.dir(rootProject.layout.projectDirectory.dir(".magicpaper/skill-packages"))
    outputs.file(rootProject.layout.projectDirectory.file("docs/desktop-ui/active-binding.json"))
}
