package io.aequicor.magicpaper.data.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileTreesTest {
    // A worktree can link to folders the application does not own; deleting it removes the link, never the target.
    @Test fun deletesTheTreeAndOnlyTheLinksThatLeaveIt() {
        val scratch = Files.createTempDirectory("file-trees")
        try {
            val outside = Files.createDirectories(scratch.resolve("outside/kept"))
            Files.writeString(outside.resolve("file.txt"), "owned elsewhere")
            val root = scratch.resolve("root")
            Files.createDirectories(root.resolve("nested/deeper"))
            Files.writeString(root.resolve("nested/deeper/file.txt"), "tree")
            root.resolve("nested/read-only.txt").also { Files.writeString(it, "tree") }.toFile().setReadOnly()
            Files.createSymbolicLink(root.resolve("nested/directory-link"), scratch.resolve("outside"))
            Files.createSymbolicLink(root.resolve("file-link"), outside.resolve("file.txt"))

            deleteTree(root)

            assertFalse(Files.exists(root))
            assertEquals("owned elsewhere", Files.readString(outside.resolve("file.txt")))
            deleteTree(root)
            assertTrue(Files.isDirectory(outside), "a missing tree is already deleted")
        } finally { scratch.toFile().deleteRecursively() }
    }
}
