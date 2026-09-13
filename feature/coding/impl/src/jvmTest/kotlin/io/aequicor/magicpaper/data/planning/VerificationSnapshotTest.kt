package io.aequicor.magicpaper.data.planning

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class VerificationSnapshotTest {
    @Test fun hashesIndexWorkingTreeAndUntrackedWithoutChangingUserIndex() = runTest {
        val root = Files.createTempDirectory("magicpaper-snapshot-").toFile()
        fun git(vararg args: String): String {
            val p = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { output }; return output
        }
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
}
