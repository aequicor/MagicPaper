package io.aequicor.magicpaper.data.research

import java.nio.file.*
import kotlin.test.*

class ResearchWorkspacePolicyTest {
    @Test fun manifestsCannotGrantSourcesGitOrLinkedDirectories() {
        val root = Files.createTempDirectory("research-policy-").toRealPath()
        try {
            val project = Files.createDirectory(root.resolve("project"))
            val scratch = Files.createDirectory(root.resolve("scratch"))
            Files.writeString(project.resolve("build.gradle.kts"), "// build")
            val module = Files.createDirectories(project.resolve("module"))
            Files.writeString(module.resolve("Cargo.toml"), "[package]")
            val built = Files.createDirectories(project.resolve("build"))
            Files.writeString(built.resolve("source.kt"), "protected")
            val policy = ResearchWorkspacePolicy.inspect(project, scratch)
            assertFalse(built in policy.writable)
            assertTrue(project.resolve(".gradle") in policy.writable)
            assertTrue(module.resolve("target") in policy.writable)
            assertTrue(scratch.toRealPath() in policy.writable)
            assertFalse(project in policy.writable)
            assertContains(policy.withheld.joinToString(), "исходники")
            assertTrue(policy.writable.all { it.isAbsolute })
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun gitWorktreeMetadataAndTrackedArtifactsStayProtected() {
        val root = Files.createTempDirectory("research-git-policy-").toRealPath()
        fun git(cwd: Path, vararg args: String) {
            val p = ProcessBuilder(listOf("git") + args).directory(cwd.toFile()).redirectErrorStream(true).start()
            val text = p.inputStream.readAllBytes().decodeToString(); assertEquals(0, p.waitFor(), text)
        }
        try {
            val project = Files.createDirectory(root.resolve("project")); val scratch = Files.createDirectory(root.resolve("scratch"))
            git(project, "init", "-q"); git(project, "config", "user.name", "Test"); git(project, "config", "user.email", "test@example.test")
            Files.writeString(project.resolve("package.json"), "{}")
            Files.createDirectories(project.resolve("dist")); Files.writeString(project.resolve("dist/data.txt"), "tracked")
            git(project, "add", "."); git(project, "commit", "-qm", "base")
            val worktree = root.resolve("worktree"); git(project, "worktree", "add", "--detach", worktree.toString())
            val policy = ResearchWorkspacePolicy.inspect(worktree, scratch)
            assertFalse(worktree.resolve("dist") in policy.writable)
            assertTrue(policy.protected.any { it == project.resolve(".git").toRealPath() })
            assertTrue(policy.protected.any { it.startsWith(project.resolve(".git/worktrees")) })
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun writeDirectoriesMustNotBeInsideApplicationPolicyOrSourceRoot() {
        val root = Files.createTempDirectory("research-policy-root-").toRealPath()
        try { assertFailsWith<IllegalArgumentException> { ResearchWorkspacePolicy.inspect(root, Files.createDirectory(root.resolve("scratch"))) } }
        finally { root.toFile().deleteRecursively() }
    }
    @Test fun windowsArgumentQuotingAndLinuxMountsHaveExplicitBoundaries() {
        assertEquals("\"a b\"", WindowsResearchSandbox.quote("a b"))
        assertEquals("\"a\\\"b\"", WindowsResearchSandbox.quote("a\"b"))
        assertEquals("\"C:\\path\\\\\"", WindowsResearchSandbox.quote("C:\\path\\"))
        val root = Paths.get("/project")
        val args = LinuxResearchSandbox.arguments(ResearchWorkspacePolicy(root, listOf(root.resolve("build")), listOf(root.resolve(".git")), emptyList()), root)
        assertContains(args, "--ro-bind"); assertContains(args, "--unshare-pid"); assertContains(args, "--disable-userns")
        assertTrue(args.windowed(3).contains(listOf("--bind", "/project/build", "/project/build")))
        assertFalse(args.windowed(3).contains(listOf("--bind", "/project", "/project")))
    }
}
