package io.aequicor.magicpaper.data.planning

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class VerificationSnapshotTest {
    /** Local Git only. A fixed identity keeps commits independent from the developer's configuration. */
    private fun gitAt(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", dir.path, "-c", "user.name=Snapshot Test",
            "-c", "user.email=snapshot@localhost") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }; return output
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

    @Test fun hashesSubmodulePointerInsteadOfItsWorkingTree() = runTest {
        val root = Files.createTempDirectory("magicpaper-snapshot-submodule-").toFile()
        val nested = root.resolve("vendor/lib")
        fun git(vararg args: String) = gitAt(root, *args)
        try {
            git("init")
            root.resolve("source.txt").writeText("source")
            git("add", "source.txt")
            git("commit", "-m", "base")
            nested.mkdirs()
            gitAt(nested, "init")
            nested.resolve("dependency.txt").writeText("dependency")
            gitAt(nested, "add", "dependency.txt")
            gitAt(nested, "commit", "-m", "dependency")
            git("update-index", "--add", "--cacheinfo", "160000,${gitAt(nested, "rev-parse", "HEAD").trim()},vendor/lib")
            git("commit", "-m", "Record the dependency as a submodule")
            // GitTaskWorkspace.clean() demands this; only the snapshot stood in the way of the delivery.
            assertEquals("", git("status", "--porcelain", "--untracked-files=all").trim())
            val indexBefore = git("ls-files", "--stage")
            val recorded = verificationSnapshot(root.path)
            // A managed worktree starts a submodule absent or empty; the delivered pointer must not depend on those bytes.
            nested.deleteRecursively()
            assertEquals(recorded, verificationSnapshot(root.path))
            nested.mkdirs()
            gitAt(nested, "init")
            gitAt(nested, "commit", "-m", "foreign history", "--allow-empty")
            assertEquals(recorded, verificationSnapshot(root.path))
            assertEquals(indexBefore, git("ls-files", "--stage"))
            // Advancing the recorded commit is the only submodule change this project carries.
            git("update-index", "--cacheinfo", "160000,${"1".repeat(40)},vendor/lib")
            assertNotEquals(recorded, verificationSnapshot(root.path))
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
