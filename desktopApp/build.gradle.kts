import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import java.io.File

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":app"))
    implementation(project(":designSystem"))
    implementation(libs.androidx.lifecycle.viewmodelCompose)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
    // JBR WindowDecorations API: designSystem declares it as compileOnly so the
    // KMP module doesn't force JBR on web/android. The desktop host needs the
    // classes at runtime for WindowsTitleBarController.
    implementation(libs.jbr.api)

    testImplementation(libs.kotlin.testJunit)
}

val jbr21 = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
    vendor.set(JvmVendorSpec.JETBRAINS)
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

// Single source of the shipped version: nativeDistributions, jpackage arguments and
// portable archive names must never drift apart.
val appVersion = "1.0.0"

compose.desktop {
    application {
        mainClass = "io.aequicor.magicpaper.MainKt"
        // Custom title bars are implemented by JetBrains Runtime. Use the same
        // runtime for local launches and the self-contained native distribution.
        javaHome = jbr21.get().metadata.installationPath.asFile.absolutePath

        buildTypes.release.proguard {
            // Bundled libraries reference optional integrations (JUnit, JMX,
            // mail/JMS, Ant tasks) that are absent from the runtime classpath.
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            modules("jdk.httpserver")
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "MagicPaper"
            packageVersion = appVersion
            // Иконки пакетов генерирует: python3 assets/icon/gen_icons.py
            macOS {
                bundleID = "io.aequicor.magicpaper"
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array><dict>
                            <key>CFBundleURLName</key><string>io.aequicor.magicpaper</string>
                            <key>CFBundleURLSchemes</key><array><string>magicpaper</string></array>
                            <key>CFBundleTypeRole</key><string>Viewer</string>
                        </dict></array>
                    """.trimIndent()
                }
                iconFile.set(project.file("../assets/icon/dist/magicpaper.icns"))
            }
            windows {
                iconFile.set(project.file("../assets/icon/dist/magicpaper.ico"))
                menu = true
                menuGroup = "MagicPaper"
            }
            linux {
                iconFile.set(project.file("../assets/icon/dist/magicpaper_512.png"))
            }
        }
    }
}

// Optional distribution includes the editor executable; the app never links its implementation.
if (providers.gradleProperty("paperEditor").orNull == "true") {
    val editorResources = layout.buildDirectory.dir("paper-editor-resources")
    val bundlePaperEditor by tasks.registering(Sync::class) {
        dependsOn(":tools:paper-editor:createDistributable")
        from(project(":tools:paper-editor").layout.buildDirectory.dir("compose/binaries/main/app"))
        into(editorResources.map { it.dir("common/paper-editor") })
        eachFile { if (file.canExecute()) permissions { unix("755") } }
    }
    compose.desktop.application.nativeDistributions.appResourcesRootDir.set(editorResources)
    tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(bundlePaperEditor) }
    // Compose 1.11 copies app resources with File.copyTo, dropping executable bits.
    // Restore the source permissions in the final image so the nested launcher can run.
    val resourcesPrefix = when {
        System.getProperty("os.name").startsWith("Mac") -> ".app/Contents/app/resources/paper-editor"
        System.getProperty("os.name").startsWith("Windows") -> "/app/resources/paper-editor"
        else -> "/lib/app/resources/paper-editor"
    }
    tasks.withType<AbstractJPackageTask>().configureEach {
        if (targetFormat == TargetFormat.AppImage) doLast {
            val source = editorResources.get().dir("common/paper-editor").asFile
            val destination = destinationDir.get().asFile.resolve(packageName.get() + resourcesPrefix)
            source.walkTopDown().filter { it.isFile && it.canExecute() }.forEach { original ->
                val copied = destination.resolve(original.relativeTo(source))
                check(copied.isFile && copied.setExecutable(true, false)) { "Cannot preserve Paper Editor executable permissions" }
            }
        }
    }
}

// Compose recreates its internal jpackage resource directory inside the package
// action. Build the DEB from the same Compose app image with an explicit resource
// directory so registration is installed, upgraded and removed by the package.
val packagingHost = System.getProperty("os.name").lowercase()
if (packagingHost.startsWith("linux")) {
    // Resolve packaging inputs at configuration time; the Exec action must not
    // touch `project` at execution time (configuration-cache requirement).
    val desktopEntry = layout.projectDirectory.file("packaging/MagicPaper.desktop")
    val linuxIconPath = project.file("../assets/icon/dist/magicpaper_512.png").absolutePath
    // Capture only serializable values in the Exec action: no script properties,
    // no outer receivers (configuration-cache requirement).
    val jbrDirectory = jbr21.get().metadata.installationPath.asFile
    listOf(false, true).forEach { release ->
        val variant = if (release) "main-release" else "main"
        val variantTask = if (release) "Release" else ""
        val image = layout.buildDirectory.dir("compose/binaries/$variant/app/MagicPaper")
        val resources = layout.buildDirectory.dir("compose/protocol-resources/$variant")
        val destination = layout.buildDirectory.dir("compose/binaries/$variant/deb")
        val installer = tasks.register<Exec>("package${variantTask}MagicPaperDeb") {
            group = "compose desktop"
            notCompatibleWithConfigurationCache("Native packaging prepares platform-specific JBR resource templates")
            description = "Packages MagicPaper with the magicpaper:// protocol handler."
            dependsOn("create${variantTask}Distributable")
            inputs.dir(image)
            inputs.dir(layout.projectDirectory.dir("packaging"))
            outputs.dir(destination)
            // Package tooling owns temporary paths and is intentionally rerun.
            outputs.upToDateWhen { false }

            // All jpackage arguments are known at configuration time; keep the
            // execution-time action limited to file preparation with local,
            // serializable captures only (configuration-cache requirement).
            val resourceDir = resources.get().asFile
            val outputDir = destination.get().asFile
            val options = listOf(
                jbrDirectory.resolve("bin/jpackage").absolutePath,
                "--type", "deb",
                "--app-image", image.get().asFile.absolutePath,
                "--dest", outputDir.absolutePath,
                "--resource-dir", resourceDir.absolutePath,
                "--name", "MagicPaper", "--app-version", appVersion,
                "--linux-shortcut", "--icon", linuxIconPath,
            )
            commandLine(options)
            doFirst {
                resourceDir.mkdirs()
                desktopEntry.asFile.copyTo(resourceDir.resolve("MagicPaper.desktop"), overwrite = true)
                outputDir.mkdirs()
                // Only remove the previous artifact produced by this package task.
                outputDir.listFiles()?.filter { it.extension == "deb" }?.forEach { it.delete() }
            }
        }
        tasks.matching { it.name == "package${variantTask}Deb" }.configureEach {
            enabled = false
            dependsOn(installer)
        }
    }
}

// Windows packaging: the Inno Setup installer plus a no-install portable ZIP, both
// over the same release app-image. jpackage's MSI is retired: it installed
// per-machine (elevation required), registered the protocol under HKLM and needed a
// manually provisioned WiX toolchain. Inno Setup installs per-user without admin
// rights, registers magicpaper:// under HKCU and offers the launch/desktop-icon
// choices jpackage cannot express. Meaningful only on a Windows host —
// createReleaseDistributable builds the image for the build OS.
if (packagingHost.startsWith("windows")) {
    val innoScript = layout.projectDirectory.file("packaging/windows/MagicPaper.iss")
    val innoOutput = layout.buildDirectory.dir("innosetup")
    val windowsIconPath = project.file("../assets/icon/dist/magicpaper.ico").absolutePath
    val imageDir = layout.buildDirectory.dir("compose/binaries/main-release/app/MagicPaper").get().asFile.absolutePath
    // ISCC discovery: explicit override first, then standard install dirs, then PATH.
    val isccCandidates = buildList {
        System.getenv("INNO_SETUP_PATH")?.takeIf { it.isNotBlank() }?.let { override ->
            val overridden = File(override)
            add(if (overridden.isFile) overridden.absolutePath else File(overridden, "ISCC.exe").absolutePath)
        }
        add("C:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe")
        add("C:\\Program Files\\Inno Setup 6\\ISCC.exe")
        addAll((System.getenv("PATH") ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }.map { File(it, "ISCC.exe").absolutePath })
    }
    val packageReleaseInnoSetup by tasks.registering(Exec::class) {
        group = "compose desktop"
        description = "Builds the per-user Windows installer (Inno Setup) from the release app-image."
        dependsOn("createReleaseDistributable")
        inputs.dir(imageDir)
        inputs.file(innoScript)
        outputs.dir(innoOutput)
        // Installer embedding stamps build time; packaging is intentionally rerun.
        outputs.upToDateWhen { false }
        val outDir = innoOutput.get().asFile.absolutePath
        val scriptPath = innoScript.asFile.absolutePath
        val iconPath = windowsIconPath
        val appImageDir = imageDir
        val version = appVersion
        val candidates = isccCandidates
        doFirst {
            val iscc = candidates.firstOrNull { File(it).isFile }
                ?: throw GradleException("Inno Setup 6 compiler (ISCC.exe) not found. " +
                    "Install Inno Setup or set INNO_SETUP_PATH to its directory. Searched: " +
                    candidates.joinToString(", "))
            File(outDir).mkdirs()
            commandLine(iscc, "/DAppVersion=$version", "/DAppDir=$appImageDir", "/DSetupIcon=$iconPath",
                "/O$outDir", "/FMagicPaper-$version-setup", "/Qp", scriptPath)
        }
    }

    // Portable distribution: unpack and run without installing or registering anything;
    // data stays in the user-owned ~/.MagicPaper directory.
    val packageReleasePortableZip by tasks.registering(Zip::class) {
        group = "compose desktop"
        description = "Packages the release app-image into a portable (no-install, no-admin) Windows ZIP."
        dependsOn("createReleaseDistributable")
        from(layout.buildDirectory.dir("compose/binaries/main-release/app/MagicPaper"))
        archiveFileName.set("MagicPaper-$appVersion-portable-windows-x64.zip")
        destinationDirectory.set(layout.buildDirectory.dir("portable"))
    }
}

// Isolated native-window checks never create the application runtime or open user data.
tasks.withType<Test>().configureEach {
    val nativeWindowTest = providers.gradleProperty("magicpaper.window.native").orElse("false").get()
    systemProperty("magicpaper.window.native", nativeWindowTest)
    systemProperty("magicpaper.computer.input.native", providers.gradleProperty("magicpaper.computer.input.native").orElse("false").get())
    if (nativeWindowTest == "true" && System.getProperty("os.name").startsWith("Mac")) {
        // Test synchronization with the OS animation completion callback only.
        jvmArgs("--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED")
    }
}
