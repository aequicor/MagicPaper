import org.gradle.language.jvm.tasks.ProcessResources

plugins { id("magicpaper.jvm-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":magic-agent:computer:api"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:logging"))
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
        }
        jvmMain.dependencies {
            implementation(libs.oshi.core)
            implementation(project(":designSystem"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("magicpaper.application.native", providers.gradleProperty("magicpaper.application.native").getOrElse("false"))
    systemProperty("magicpaper.computer.windows.native", providers.gradleProperty("magicpaper.computer.windows.native").getOrElse("false"))
    systemProperty("magicpaper.application.testInstallation", layout.buildDirectory.dir("application-use/native-acceptance").get().asFile.absolutePath)
}

// Resource names and installation paths remain stable across the owner extraction.
val applicationResources = layout.buildDirectory.dir("application-use/resources")
val applicationBinary = applicationResources.map { it.file("computer/application-use").asFile }
val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val bundleApplicationUse by tasks.registering(Exec::class) {
    enabled = hostOs.contains("mac") || hostOs.contains("darwin")
    val binary = applicationBinary.get()
    inputs.file("src/jvmMain/native/application-use.swift")
    outputs.file(binary)
    commandLine("xcrun", "swiftc", "-parse-as-library", "-O", "-target",
        "${if (hostArch.contains("aarch64") || hostArch.contains("arm64")) "arm64" else "x86_64"}-apple-macosx14.0",
        "src/jvmMain/native/application-use.swift", "-o", binary.absolutePath)
    doFirst { binary.parentFile.mkdirs() }
}
tasks.named<ProcessResources>("jvmProcessResources") {
    from(applicationResources)
    dependsOn(bundleApplicationUse)
}
