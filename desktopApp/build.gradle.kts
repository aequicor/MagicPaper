import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.util.zip.ZipFile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec

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

compose.desktop {
    application {
        mainClass = "io.aequicor.magicpaper.MainKt"
        // Custom title bars are implemented by JetBrains Runtime. Use the same
        // runtime for local launches and the self-contained native distribution.
        javaHome = jbr21.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            modules("jdk.httpserver")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MagicPaper"
            packageVersion = "1.0.0"
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
// action. Build MSI/DEB from the same Compose app image with an explicit resource
// directory so registration is installed, upgraded and removed by the package.
val packagingHost = System.getProperty("os.name").lowercase()
val protocolPackageType = when {
    packagingHost.startsWith("windows") -> "msi"
    packagingHost.startsWith("linux") -> "deb"
    else -> null
}
if (protocolPackageType != null) {
    listOf(false, true).forEach { release ->
        val variant = if (release) "main-release" else "main"
        val variantTask = if (release) "Release" else ""
        val formatTask = protocolPackageType.replaceFirstChar(Char::uppercaseChar)
        val image = layout.buildDirectory.dir("compose/binaries/$variant/app/MagicPaper")
        val resources = layout.buildDirectory.dir("compose/protocol-resources/$variant")
        val destination = layout.buildDirectory.dir("compose/binaries/$variant/$protocolPackageType")
        val installer = tasks.register<Exec>("package${variantTask}MagicPaper$formatTask") {
            group = "compose desktop"
            notCompatibleWithConfigurationCache("Native packaging prepares platform-specific JBR resource templates")
            description = "Packages MagicPaper with the magicpaper:// protocol handler."
            dependsOn("create${variantTask}Distributable")
            inputs.dir(image)
            inputs.dir(layout.projectDirectory.dir("packaging"))
            outputs.dir(destination)
            // Package tooling owns temporary paths and is intentionally rerun.
            outputs.upToDateWhen { false }
            doFirst {
                val resourceDir = resources.get().asFile.apply { mkdirs() }
                val javaDirectory = jbr21.get().metadata.installationPath.asFile
                if (protocolPackageType == "msi") {
                    val template = ZipFile(javaDirectory.resolve("jmods/jdk.jpackage.jmod")).use { archive ->
                        val entry = archive.getEntry("classes/jdk/jpackage/internal/resources/main.wxs")
                            ?: error("JBR jpackage main.wxs template is unavailable")
                        archive.getInputStream(entry).bufferedReader().use { it.readText() }
                    }
                    val featureMarker = "<ComponentGroupRef Id=\"FileAssociations\"/>"
                    check(template.contains(featureMarker) && template.contains("</Product>")) {
                        "JBR jpackage WiX template changed; review protocol integration before shipping"
                    }
                    resourceDir.resolve("main.wxs").writeText(template
                        .replace(featureMarker, "$featureMarker\n      <ComponentRef Id=\"MagicPaperProtocol\"/>")
                        .replace("</Product>", project.file("packaging/protocol.wxi").readText() + "\n  </Product>"))
                } else {
                    project.file("packaging/MagicPaper.desktop").copyTo(resourceDir.resolve("MagicPaper.desktop"), overwrite = true)
                }
                val output = destination.get().asFile.apply { mkdirs() }
                // Only remove the previous artifact produced by this package task.
                output.listFiles()?.filter { it.extension == protocolPackageType }?.forEach { it.delete() }
                val executableName = if (protocolPackageType == "msi") "jpackage.exe" else "jpackage"
                val options = mutableListOf(
                    javaDirectory.resolve("bin/$executableName").absolutePath,
                    "--type", protocolPackageType,
                    "--app-image", image.get().asFile.absolutePath,
                    "--dest", output.absolutePath,
                    "--resource-dir", resourceDir.absolutePath,
                    "--name", "MagicPaper", "--app-version", "1.0.0",
                )
                if (protocolPackageType == "msi") {
                    options += listOf("--win-menu", "--win-menu-group", "MagicPaper",
                        "--icon", project.file("../assets/icon/dist/magicpaper.ico").absolutePath)
                } else {
                    options += listOf("--linux-shortcut",
                        "--icon", project.file("../assets/icon/dist/magicpaper_512.png").absolutePath)
                }
                commandLine(options)
            }
        }
        tasks.matching { it.name == "package$variantTask$formatTask" }.configureEach {
            enabled = false
            dependsOn(installer)
        }
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
