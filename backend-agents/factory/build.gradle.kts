plugins { id("magicpaper.jvm-library") }

// An included backend supplies its own ServiceLoader contribution. Keep discovery
// inside the factory so neither :app nor the host runtime enumerates implementations.
// The architecture verifier derives the same edges from settings for cycle checks.
val backendAgentProjects = rootProject.subprojects.filter {
    it.path.startsWith(":backend-agents:") &&
        it.path !in setOf(":backend-agents:api", ":backend-agents:factory", ":backend-agents:lifecycle:impl") && it.buildFile.isFile
}.map { it.path }.sorted()

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":backend-agents:api")) }
        jvmMain.dependencies {
            implementation(project(":backend-agents:lifecycle:impl"))
            backendAgentProjects.forEach { implementation(project(it)) }
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/nativeLifecycle")) }
    }
}
