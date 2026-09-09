package io.aequicor.magicpaper.data.research

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.*
import kotlin.test.*

/** Run on each native OS with -Pmagicpaper.research.native=true. Never replaces OS tests with mocks. */
class ResearchSandboxNativeTest {
    private val enabled get() = System.getProperty("magicpaper.research.native") == "true"
    private val windows get() = System.getProperty("os.name").startsWith("Windows")
    private fun shell(unix: String, win: String) = if (windows) listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", win)
        else listOf("/bin/sh", "-c", unix)

    @Test fun nativeRunnerReadsAndBuildsButCannotWriteSourcesOrGit() = runBlocking {
        if (!enabled) return@runBlocking
        val root = Files.createTempDirectory("research-native-")
        try {
            val project = Files.createDirectory(root.resolve("project"))
            Files.writeString(project.resolve("build.gradle"), "// fixture")
            Files.writeString(project.resolve("source.kt"), "CURRENT")
            val git = Files.createDirectories(project.resolve(".git"))
            // Use a real repo so metadata lookup has its production shape.
            val init = ProcessBuilder("git", "init", "-q").directory(project.toFile()).start(); assertEquals(0, init.waitFor())
            val before = Files.readAllBytes(git.resolve("HEAD"))
            val runner = ResearchCheckRunner(root.resolve("runtime"))
            val result = runner.run(project, "s", shell(
                "cat source.kt; printf forbidden > source.kt; rm source.kt; mv source.kt moved.kt; printf no > created.kt; printf no > .git/HEAD; sh -c 'printf child > source.kt'; ln source.kt build/hard.txt; printf no > build/hard.txt; ln -s ../source.kt build/link.txt; printf no > build/link.txt; mkdir -p build; printf artifact > build/ok.txt; printf CHECK_DONE",
                "\$ErrorActionPreference='SilentlyContinue'; Get-Content source.kt; Set-Content source.kt forbidden; Remove-Item source.kt; Rename-Item source.kt moved.kt; Set-Content created.kt no; Set-Content .git/HEAD no; powershell.exe -NoProfile -Command 'Set-Content source.kt child'; New-Item -ItemType HardLink -Path build/hard.txt -Target source.kt; Set-Content build/hard.txt no; New-Item -ItemType SymbolicLink -Path build/link.txt -Target ../source.kt; Set-Content build/link.txt no; New-Item -ItemType Directory -Force build; Set-Content build/ok.txt artifact; Write-Output CHECK_DONE"))
            assertNull(result.blockedReason, result.toString())
            assertEquals(0, result.exitCode, result.toString())
            assertContains(result.output, "CURRENT"); assertContains(result.output, "CHECK_DONE")
            assertEquals("CURRENT", Files.readString(project.resolve("source.kt")))
            assertContentEquals(before, Files.readAllBytes(git.resolve("HEAD")))
            assertFalse(Files.exists(project.resolve("moved.kt"))); assertFalse(Files.exists(project.resolve("created.kt")))
            assertEquals("artifact", Files.readString(project.resolve("build/ok.txt")).trim())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun linkedArtifactPathsCannotOverwriteSources() = runBlocking {
        if (!enabled) return@runBlocking
        val root = Files.createTempDirectory("research-links-")
        try {
            val project = Files.createDirectory(root.resolve("project"))
            Files.writeString(project.resolve("build.gradle"), "// fixture")
            val source = Files.writeString(project.resolve("source.txt"), "CURRENT")
            val output = Files.createDirectory(project.resolve("build"))
            Files.createLink(output.resolve("linked.txt"), source)
            val runner = ResearchCheckRunner(root.resolve("runtime"))
            val result = runner.run(project, "s", shell("printf forbidden > build/linked.txt", "Set-Content build/linked.txt forbidden; if (-not \$?) { exit 1 }"))
            assertTrue(result.blockedReason != null || result.exitCode != 0, result.toString())
            assertEquals("CURRENT", Files.readString(source))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun completedCommandCannotLeaveItsChildWritingArtifacts() = runBlocking {
        if (!enabled) return@runBlocking
        val root = Files.createTempDirectory("research-child-")
        try {
            val project = Files.createDirectory(root.resolve("project"))
            Files.writeString(project.resolve("build.gradle"), "// fixture")
            val runner = ResearchCheckRunner(root.resolve("runtime"))
            val result = runner.run(project, "s", shell("(sleep 2; printf late > build/late.txt) & printf COMPLETE",
                "Start-Process powershell.exe -ArgumentList '-NoProfile','-NonInteractive','-Command','Start-Sleep 2; Set-Content build/late.txt late'; Write-Output COMPLETE"))
            assertNull(result.blockedReason, result.toString()); assertContains(result.output, "COMPLETE")
            delay(2500)
            assertFalse(Files.exists(project.resolve("build/late.txt")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun missingSandboxReportsUnavailabilityAndNeverRunsCommand() = runBlocking {
        val root = Files.createTempDirectory("research-unavailable-")
        try {
            val project = Files.createDirectory(root.resolve("project"))
            val runner = ResearchCheckRunner(root.resolve("runtime"), sandbox = { error("sandbox missing") })
            val result = runner.run(project, "s", shell("touch created.txt", "Set-Content created.txt no"))
            assertContains(result.blockedReason.orEmpty(), "sandbox missing")
            assertFalse(Files.exists(project.resolve("created.txt")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun repeatChecksPreserveNewIgnoredFilesAndReleaseTemporaryCaches() = runBlocking {
        if (!enabled) return@runBlocking
        val root = Files.createTempDirectory("research-repeat-")
        try {
            val project = Files.createDirectory(root.resolve("project"))
            Files.writeString(project.resolve("package.json"), "{}")
            val runner = ResearchCheckRunner(root.resolve("runtime"))
            fun command() = shell("printf output > dist/result.js", "Set-Content dist/result.js output")
            val first = runner.run(project, "s", command())
            assertNull(first.blockedReason, first.toString()); assertEquals(0, first.exitCode, first.toString())
            // A fresh service loads the attestation; restart does not make user files writable.
            val next = ResearchCheckRunner(root.resolve("runtime"))
            val second = next.run(project, "s", command())
            assertNull(second.blockedReason, second.toString()); assertEquals(0, second.exitCode, second.toString())
            Files.writeString(project.resolve("dist/notes.txt"), "USER")
            val third = next.run(project, "s", shell("printf forbidden > dist/notes.txt", "Set-Content dist/notes.txt forbidden; if (-not \$?) { exit 1 }"))
            assertTrue(third.blockedReason != null || third.exitCode != 0, third.toString())
            assertEquals("USER", Files.readString(project.resolve("dist/notes.txt")))
            Files.list(root.resolve("runtime")).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith("run-") }) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cancellationKillsChildrenAndCleansCaches() = runBlocking {
        if (!enabled) return@runBlocking
        val root = Files.createTempDirectory("research-cancel-")
        try {
            val project = Files.createDirectory(root.resolve("project"))
            Files.writeString(project.resolve("build.gradle"), "// fixture")
            val runner = ResearchCheckRunner(root.resolve("runtime"))
            val job = async { runner.run(project, "s", shell("printf STARTED; sleep 3; printf late > build/late.txt",
                "Write-Output STARTED; Start-Sleep 3; Set-Content build/late.txt late")) }
            withTimeout(30_000) { runner.progress.first { "STARTED" in it.output } }
            job.cancelAndJoin(); delay(3200)
            assertFalse(Files.exists(project.resolve("build/late.txt")))
            Files.list(root.resolve("runtime")).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith("run-") }) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun unixDetachedProcessesCannotEscapeCheckLifetime() = runBlocking {
        if (!enabled || windows) return@runBlocking
        val root = Files.createTempDirectory("research-detach-")
        try {
            val project = Files.createDirectory(root.resolve("project"))
            Files.writeString(project.resolve("build.gradle"), "// fixture")
            val source = Files.writeString(root.resolve("probe.c"), """
                #include <unistd.h>
                #include <spawn.h>
                #include <stdio.h>
                extern char **environ;
                int main(void) {
                  if (fork() == 0) { setsid(); sleep(2); FILE *f = fopen("build/detached.txt", "w"); if (f) { fputs("late", f); fclose(f); } _exit(0); }
                  posix_spawnattr_t attr; posix_spawnattr_init(&attr);
                  posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETPGROUP); posix_spawnattr_setpgroup(&attr, 0);
                  char *args[] = {"/bin/sh", "-c", "sleep 2; printf late > build/spawned.txt", NULL}; pid_t pid;
                  int result = posix_spawn(&pid, "/bin/sh", NULL, &attr, args, environ);
                  printf("ATTEMPTED:%d\\n", result); fflush(stdout); usleep(200000); return 0;
                }
            """.trimIndent())
            // Linux hides the host /tmp, so the fixture executable belongs to the visible read-only project.
            val binary = project.resolve("fixture-probe")
            val compiler = ProcessBuilder("cc", source.toString(), "-o", binary.toString()).redirectErrorStream(true).start()
            val log = compiler.inputStream.readAllBytes().decodeToString(); assertEquals(0, compiler.waitFor(), log)
            val result = ResearchCheckRunner(root.resolve("runtime")).run(project, "s", listOf(binary.toString()))
            assertNull(result.blockedReason, result.toString())
            assertContains(result.output, "ATTEMPTED:")
            delay(2500)
            assertFalse(Files.exists(project.resolve("build/detached.txt")))
            assertFalse(Files.exists(project.resolve("build/spawned.txt")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun applicationCrashTerminatesCheckAndRecoveryRemovesItsTemporaryFiles() = runBlocking {
        if (!enabled) return@runBlocking
        val root = Files.createTempDirectory("research-crash-")
        try {
            val project = Files.createDirectory(root.resolve("project"))
            Files.writeString(project.resolve("build.gradle"), "// fixture")
            val discovered = generateSequence(ResearchSandboxNativeTest::class.java.classLoader) { it.parent }.filterIsInstance<java.net.URLClassLoader>()
                .flatMap { it.urLs.asSequence() }.filter { it.protocol == "file" }.map { Paths.get(it.toURI()).toString() }
            val classpath = (discovered + System.getProperty("java.class.path").split(java.io.File.pathSeparator).asSequence())
                .filter { it.isNotBlank() }.distinct().joinToString(java.io.File.pathSeparator)
            check(classpath.isNotEmpty()) { "Нужен classpath нативной тестовой JVM" }
            val javaExecutable = Paths.get(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java").toString()
            val output = root.resolve("host.log").toFile()
            val child = ProcessBuilder(javaExecutable, "-cp", classpath, ResearchCrashFixture::class.java.name, root.toString())
                .redirectErrorStream(true).redirectOutput(output).start()
            try {
                assertTrue(child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), output.readText())
                assertEquals(0, child.exitValue(), output.readText())
                assertTrue(Files.exists(root.resolve("started")), output.readText())
                delay(2500)
                assertFalse(Files.exists(project.resolve("build/late.txt")))
                ResearchCheckRunner(root.resolve("runtime")).reconcile("crash")
                Files.list(root.resolve("runtime")).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith("run-") }) }
            } finally { child.destroyForcibly() }
        } finally { root.toFile().deleteRecursively() }
    }
}

/** Separate JVM deliberately halts without shutdown hooks; native ownership must survive it. */
internal object ResearchCrashFixture {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val root = Paths.get(args.single())
        val runner = ResearchCheckRunner(root.resolve("runtime"))
        val command = if (System.getProperty("os.name").startsWith("Windows"))
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Write-Output STARTED; Start-Sleep 2; Set-Content build/late.txt late")
        else listOf("/bin/sh", "-c", "printf STARTED; sleep 2; printf late > build/late.txt")
        launch { val result = runner.run(root.resolve("project"), "crash", command); error(result.toString()) }
        withTimeout(20_000) { runner.progress.first { "STARTED" in it.output } }
        Files.writeString(root.resolve("started"), "yes")
        Runtime.getRuntime().halt(0)
    }
}
