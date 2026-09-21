package io.aequicor.magicpaper.data.planning

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class VerificationSnapshotTest {
    private suspend fun verificationSnapshot(path: String): String = io.aequicor.magicpaper.data.planning.verificationSnapshot(
        path, OwnedGitCommands.readOnly(testGitChecks(), path))

    /** Local Git only. A fixed identity keeps commits independent from the developer's configuration. */
    private fun gitAt(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", dir.path, "-c", "user.name=Snapshot Test",
            "-c", "user.email=snapshot@localhost") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }; return output
    }

    private class GitlinkFixture : AutoCloseable {
        val root = Files.createTempDirectory("magicpaper-gitlink-snapshot-").toFile()
        val module = root.resolve("tools/mission-visualization").apply { mkdirs() }
        init {
            git(root, "init")
            git(module, "init")
            module.resolve("source.txt").writeText("base")
            module.resolve(".gitignore").writeText("build/\n")
            git(module, "add", "."); git(module, "commit", "-m", "base")
            root.resolve(".gitmodules").writeText("[submodule \"visualization\"]\n\tpath = tools/mission-visualization\n\turl = ./fixture\n")
            git(root, "add", "tools/mission-visualization", ".gitmodules")
            git(root, "commit", "-m", "gitlink")
            git(root, "submodule", "absorbgitdirs")
        }
        override fun close() { root.deleteRecursively() }
        fun git(dir: File, vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@localhost",
                "-c", "commit.gpgSign=false", "-C", dir.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            return output.trim()
        }
    }

    @Test fun uninitializedGitlinkIsAValidSnapshotWithoutChangingIndex() = runTest {
        GitlinkFixture().use { f ->
            f.module.deleteRecursively(); f.module.mkdirs()
            val index = f.git(f.root, "ls-files", "--stage")
            val empty = verificationSnapshot(f.root.path)
            assertEquals(empty, verificationSnapshot(f.root.path))
            assertEquals(index, f.git(f.root, "ls-files", "--stage"))
            f.module.resolve("unexpected.txt").writeText("must be observed")
            assertNotEquals(empty, verificationSnapshot(f.root.path))
        }
    }

    @Test fun initializedGitlinkIncludesHeadIndexAndActualBytes() = runTest {
        GitlinkFixture().use { f ->
            assertTrue(f.module.resolve(".git").isFile)
            f.git(f.root, "config", "submodule.visualization.ignore", "all")
            val index = f.git(f.root, "ls-files", "--stage")
            val baseline = verificationSnapshot(f.root.path)
            f.module.resolve("source.txt").writeText("modified")
            val dirty = verificationSnapshot(f.root.path)
            assertNotEquals(baseline, dirty)
            f.git(f.module, "add", "source.txt")
            val staged = verificationSnapshot(f.root.path)
            assertNotEquals(dirty, staged)
            f.module.resolve("new.txt").writeText("untracked")
            val untracked = verificationSnapshot(f.root.path)
            assertNotEquals(staged, untracked)
            f.module.resolve("build").mkdirs()
            f.module.resolve("build/output.txt").writeText("ignored")
            assertEquals(untracked, verificationSnapshot(f.root.path))
            f.git(f.module, "commit", "-m", "changed")
            val committed = verificationSnapshot(f.root.path)
            f.git(f.module, "commit", "--allow-empty", "-m", "same tree new commit")
            assertNotEquals(committed, verificationSnapshot(f.root.path))
            assertEquals(index, f.git(f.root, "ls-files", "--stage"))
        }
    }

    @Test fun aDirectoryReplacingAnOrdinaryTrackedFileIsStillRejected() = runTest {
        GitlinkFixture().use { f ->
            f.root.resolve("ordinary.txt").writeText("tracked")
            f.git(f.root, "add", "ordinary.txt")
            f.root.resolve("ordinary.txt").delete()
            f.root.resolve("ordinary.txt").mkdirs()
            assertFailsWith<IllegalStateException> { verificationSnapshot(f.root.path) }
        }
    }

    @Test fun hashesIndexWorkingTreeAndUntrackedWithoutChangingUserIndex() = runTest {
        val root = Files.createTempDirectory("magicpaper-snapshot-").toFile()
        fun git(vararg args: String) = gitAt(root, *args)
        try {
            git("init")
            root.resolve("source.txt").writeText("old")
            root.resolve(".gitignore").writeText("build/\n")
            git("add", "source.txt", ".gitignore")
            val baseline = verificationSnapshot(root.path)
            root.resolve("source.txt").writeText("new")
            val indexBefore = git("ls-files", "--stage")
            val workingChange = verificationSnapshot(root.path)
            assertNotEquals(baseline, workingChange)
            assertEquals(indexBefore, git("ls-files", "--stage"))
            git("add", "source.txt")
            val stagedChange = verificationSnapshot(root.path)
            assertNotEquals(workingChange, stagedChange)
            root.resolve("new file.txt").writeText("new untracked bytes")
            val untracked = verificationSnapshot(root.path)
            assertNotEquals(stagedChange, untracked)
            root.resolve("build").mkdir()
            root.resolve("build/test.xml").writeText("generated output")
            assertEquals(untracked, verificationSnapshot(root.path))
            root.resolve("source.txt").delete()
            assertNotEquals(untracked, verificationSnapshot(root.path))
        } finally { root.deleteRecursively() }
    }

    @Test fun hashesUntrackedNestedRepositoryAsOneEntry() = runTest {
        val root = Files.createTempDirectory("magicpaper-snapshot-nested-").toFile()
        val nested = root.resolve("vendor/lib")
        fun git(vararg args: String) = gitAt(root, *args)
        try {
            git("init")
            root.resolve("source.txt").writeText("source")
            git("add", "source.txt")
            nested.mkdirs()
            gitAt(nested, "init")
            nested.resolve("dependency.txt").writeText("dependency")
            val baseline = verificationSnapshot(root.path)
            nested.resolve("another.txt").writeText("another")
            assertEquals(baseline, verificationSnapshot(root.path))
            // An ordinary folder is still enumerated file by file.
            val notes = root.resolve("notes")
            notes.mkdirs()
            notes.resolve("note.txt").writeText("note")
            assertNotEquals(baseline, verificationSnapshot(root.path))
        } finally { root.deleteRecursively() }
    }
}
