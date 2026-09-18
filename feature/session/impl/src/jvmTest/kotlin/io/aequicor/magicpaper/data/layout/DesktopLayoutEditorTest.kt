package io.aequicor.magicpaper.data.layout

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class DesktopLayoutEditorTest {
    @Test fun publicationPreservesManualChangesAndKeepsPreviousVersion() = runBlocking {
        val directory = Files.createTempDirectory("paper-publish-")
        val target = directory.resolve("screen.layout.md")
        val editor = DesktopLayoutEditor(stateDirectory = directory.resolve("state"))
        val workspace = LayoutWorkspace("p", directory.toString(), "initial", "catalog")
        try {
            Files.writeString(target, "manual edit")
            assertFailsWith<LayoutEditorException> { editor.publish(workspace, "generated") }
            assertEquals("manual edit", Files.readString(target))
            Files.writeString(target, "initial")
            editor.publish(workspace, "generated")
            assertEquals("generated", Files.readString(target))
            assertEquals("initial", Files.readString(directory.resolve("screen.before-agent.bak")))
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun symlinkDirectoryCannotWriteOutsideProject() = runBlocking {
        io.aequicor.magicpaper.test.assumeSymbolicLinksAvailable()
        val root = Files.createTempDirectory("paper-root-"); val outside = Files.createTempDirectory("paper-outside-")
        try {
            Files.createSymbolicLink(root.resolve("design"), outside)
            val editor = DesktopLayoutEditor(executable = { root.resolve("unused") }, stateDirectory = root.resolve("state"))
            assertFailsWith<LayoutEditorException> { editor.open(CodingProject("p", "P", root.toString(), 0), "chat") }
            assertEquals(0, Files.list(outside).use { it.count() }.toInt())
        } finally { Files.deleteIfExists(root.resolve("design")); root.toFile().deleteRecursively(); outside.toFile().deleteRecursively() }
    }
}
