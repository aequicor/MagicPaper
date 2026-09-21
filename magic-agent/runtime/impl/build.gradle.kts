import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.ExecOperations

plugins { id("magicpaper.jvm-compose-library") }

kotlin {
    sourceSets {
        commonTest { kotlin.srcDir(rootProject.file("testSupport/native")) }
        commonTest { kotlin.srcDir(rootProject.file("testSupport/workspace")) }
        commonTest { kotlin.srcDir(rootProject.file("testSupport/media")) }
        commonMain.dependencies {
            api(project(":magic-agent:checks:api"))
            implementation(project(":magic-agent:computer:api"))
            api(project(":magic-agent:organism:api"))
            api(project(":magic-agent:planning:api"))
            implementation(project(":magic-agent:browser:api"))
            implementation(project(":magic-common:research"))
            implementation(project(":magic-common:prompts"))
            implementation(project(":magic-common:questionnaire:api"))
            implementation(project(":backend-agents:api"))
            implementation(project(":backend-agents:factory"))
            implementation(project(":core:logging"))
            api(project(":core:model"))
            api(project(":magic-agent:runtime:api"))
            implementation(project(":magic-common:transcript"))
            implementation(project(":core:platform"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:ai:api"))
            implementation(project(":magic-common:tools:api"))
            implementation(project(":feature:settings:api"))
            implementation(project(":feature:docs:api"))
            implementation(project(":feature:plugins:api"))
            implementation(project(":feature:skills:api"))
            implementation(project(":designSystem"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.decompose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.ktor.clientCore)
        }
        jvmMain.dependencies {
            implementation(libs.oshi.core)
            implementation(libs.ktor.clientCio)
        }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/rendering")) }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/filesystems")) }
        commonTest { kotlin.srcDir(rootProject.file("testSupport/planning")) }
        commonTest.dependencies {
            implementation(project(":magic-agent:workspace:impl"))
            implementation(project(":magic-common:tools:impl"))
            implementation(project(":magic-common:media:impl"))
            implementation(project(":magic-common:questionnaire:impl"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:storage:impl"))
            implementation(project(":core:ai:impl"))
            implementation(project(":feature:settings:impl"))
            implementation(project(":feature:skills:impl"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
        }
        jvmTest.dependencies {
            implementation(project(":magic-agent:checks:impl"))
            implementation(project(":magic-agent:computer:impl"))
            implementation(project(":magic-agent:organism:impl"))
            implementation(project(":magic-agent:planning:impl"))
            implementation(project(":magic-agent:browser:impl"))
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.markdownRenderer.m3)
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("magicpaper.browser.native", providers.gradleProperty("magicpaper.browser.native").getOrElse("false"))
    systemProperty("magicpaper.paperEditor.it", providers.gradleProperty("magicpaper.paperEditor.it").getOrElse("false"))
    val launcher = when {
        System.getProperty("os.name").startsWith("Mac") -> "PaperEditor.app/Contents/MacOS/PaperEditor"
        System.getProperty("os.name").startsWith("Windows") -> "PaperEditor/PaperEditor.exe"
        else -> "PaperEditor/bin/PaperEditor"
    }
    systemProperty("magicpaper.paperEditor.testExecutable", providers.gradleProperty("magicpaper.paperEditor.testExecutable")
        .getOrElse(rootProject.file("tools/paper-editor/build/compose/binaries/main/app/$launcher").absolutePath))
    systemProperty("magicpaper.pi.it", providers.gradleProperty("magicpaper.pi.it").getOrElse("false"))
    systemProperty("magicpaper.codex.it", providers.gradleProperty("magicpaper.codex.it").getOrElse("false"))
}

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

// ---- Переносимые бинарники поиска для движка pi ---------------------------

/**
 * Нативные инструменты pi работают через внешние бинарники: `find` вызывает `fd`,
 * `grep` — `rg`. Движок запускается с `PI_OFFLINE=1` (сам он не качает) и с отдельным
 * на каждую сессию `PI_CODING_AGENT_DIR`, где pi ищет `$PI_CODING_AGENT_DIR/bin`,
 * поэтому скачанное не переиспользуется между сессиями. Задача берёт закреплённые
 * релизы и кладёт их в ресурсы дистрибутива; приложение раскладывает их в общий
 * `~/.MagicPaper/coding/bin` и добавляет каталог в PATH процесса
 * (см. `PiCodingRuntime.installBundledSearchTools`).
 *
 * `-Pmagicpaper.codingTools.offline=true` пропускает загрузку — для машин, где fd и rg
 * уже стоят в системном PATH.
 */
val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val hostIsArm = hostArch.contains("aarch64") || hostArch.contains("arm64")
val codingToolsTarget = (when {
    hostOs.contains("mac") || hostOs.contains("darwin") -> "darwin"
    hostOs.contains("win") -> "win"
    else -> "linux"
}) + "-" + if (hostIsArm) "arm64" else "x64"

val fdVersion = libs.versions.coding.fd.get()
val ripgrepVersion = libs.versions.coding.ripgrep.get()
val rustArch = if (hostIsArm) "aarch64" else "x86_64"
val (fdAsset, ripgrepAsset) = when {
    codingToolsTarget.startsWith("win") ->
        "fd-v$fdVersion-$rustArch-pc-windows-msvc.zip" to "ripgrep-$ripgrepVersion-$rustArch-pc-windows-msvc.zip"
    codingToolsTarget.startsWith("darwin") ->
        "fd-v$fdVersion-$rustArch-apple-darwin.tar.gz" to "ripgrep-$ripgrepVersion-$rustArch-apple-darwin.tar.gz"
    hostIsArm ->
        "fd-v$fdVersion-aarch64-unknown-linux-gnu.tar.gz" to "ripgrep-$ripgrepVersion-aarch64-unknown-linux-gnu.tar.gz"
    else ->
        "fd-v$fdVersion-x86_64-unknown-linux-gnu.tar.gz" to "ripgrep-$ripgrepVersion-x86_64-unknown-linux-musl.tar.gz"
}

val codingToolsResources = layout.buildDirectory.dir("coding-tools/resources")
val bundleCodingSearchTools by tasks.registering(BundleCodingSearchToolsTask::class) {
    group = "build"
    description = "Bundles the fd and rg binaries required by the Pi engine's native search tools."
    bundleTarget.set(codingToolsTarget)
    toolVersions.set(mapOf("fd" to fdVersion, "rg" to ripgrepVersion))
    toolDownloads.set(
        mapOf(
            "fd" to "https://github.com/sharkdp/fd/releases/download/v$fdVersion/$fdAsset",
            "rg" to "https://github.com/BurntSushi/ripgrep/releases/download/$ripgrepVersion/$ripgrepAsset",
        )
    )
    resourceRoot.set(codingToolsResources)
    enabled = providers.gradleProperty("magicpaper.codingTools.offline").orNull?.toBooleanStrictOrNull() != true
}

// KMP обрабатывает ресурсы jvm-цели задачей jvmProcessResources: результат идёт в
// build/processedResources/jvm/main, откуда его читает PiCodingRuntime.
tasks.named<ProcessResources>("jvmProcessResources") {
    from(codingToolsResources)
    dependsOn(bundleCodingSearchTools)
}

abstract class BundleCodingSearchToolsTask : DefaultTask() {
    @get:Input abstract val bundleTarget: Property<String>

    /** Инструмент -> URL релизного архива. */
    @get:Input abstract val toolDownloads: MapProperty<String, String>

    /** Инструмент -> версия, которую должен подтвердить запуск `--version`. */
    @get:Input abstract val toolVersions: MapProperty<String, String>

    @get:OutputDirectory abstract val resourceRoot: DirectoryProperty

    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction
    fun bundle() {
        val toolsRoot = File(resourceRoot.get().asFile, "coding/tools")
        // Чужой target — другая машина сборки: в дистрибутив его бинарники не тащим.
        toolsRoot.deleteRecursively()
        val targetDir = File(toolsRoot, bundleTarget.get()).apply { mkdirs() }
        val notices = StringBuilder()
        toolDownloads.get().forEach { (tool, url) -> bundleTool(tool, url, targetDir, notices) }
        File(targetDir, "THIRD-PARTY-NOTICES.txt").writeText(notices.toString())
        // Имя каталога читается отсюда: os/arch не дублируются в Gradle и в Kotlin.
        File(toolsRoot, "target.txt").writeText("${bundleTarget.get()}\n")
    }

    private fun bundleTool(tool: String, url: String, targetDir: File, notices: StringBuilder) {
        val archive = File(temporaryDir, url.substringAfterLast('/'))
        logger.lifecycle("Coding search tools: скачиваю $tool — $url")
        URI(url).toURL().openStream().use { input -> archive.outputStream().use(input::copyTo) }
        val unpacked = File(temporaryDir, "$tool-unpacked").apply { deleteRecursively(); mkdirs() }
        if (archive.name.endsWith(".zip")) {
            unzipArchive(archive, unpacked)
        } else {
            execOperations.exec { commandLine("tar", "xzf", archive.absolutePath, "-C", unpacked.absolutePath) }
        }
        val binaryName = tool + if (bundleTarget.get().startsWith("win")) ".exe" else ""
        val source = unpacked.walkTopDown().firstOrNull { it.isFile && it.name == binaryName }
            ?: error("в архиве ${archive.name} не найден $binaryName")
        val binary = File(targetDir, binaryName)
        source.copyTo(binary)
        binary.setExecutable(true, false)
        verifyVersion(tool, binary)
        unpacked.walkTopDown().filter { it.isFile && it.name in LICENSE_FILE_NAMES }.forEach { license ->
            notices.append("=== ").append(tool).append(' ').append(license.name)
                .append(" (").append(url).append(") ===\n")
                .append(license.readText()).append("\n\n")
        }
    }

    /** Бинарник проверяется запуском: неверная архитектура или битый архив не попадут в дистрибутив. */
    private fun verifyVersion(tool: String, binary: File) {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(binary.absolutePath, "--version")
            standardOutput = output
            errorOutput = output
        }
        val expected = toolVersions.get().getValue(tool)
        val printed = output.toString(Charsets.UTF_8).trim()
        if (!printed.contains(expected)) {
            error("$tool: ожидаем версию $expected, бинарник ответил «${printed.take(160)}»")
        }
    }

    private fun unzipArchive(archive: File, destination: File) {
        val root = destination.canonicalFile
        ZipFile(archive).use { zip ->
            zip.entries().toList().forEach { entry ->
                val extracted = File(destination, entry.name)
                if (!extracted.canonicalFile.startsWith(root)) {
                    error("запись архива выходит за каталог распаковки: ${entry.name}")
                }
                if (entry.isDirectory) {
                    extracted.mkdirs()
                } else {
                    extracted.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> extracted.outputStream().use(input::copyTo) }
                }
            }
        }
    }

    private companion object {
        val LICENSE_FILE_NAMES = setOf("LICENSE-MIT", "LICENSE-APACHE", "LICENSE-APACHE-2.0", "UNLICENSE", "COPYING")
    }
}
