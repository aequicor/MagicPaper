package io.aequicor.magicpaper.data.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileKeyValueStoreTest {
    @Test
    fun longPlanningKeysSurviveReopeningUpdatingAndDeleting() = withRoot { root ->
        val prefix = "coding-log:demo-b:plan-" + "followup-blocker-instructions-".repeat(30)
        val keys = listOf(prefix + "first", prefix + "second", "ж".repeat(200))
        val store = FileKeyValueStore(root)
        keys.forEachIndexed { index, key -> store.write(key, "message-$index") }
        val reopened = FileKeyValueStore(root)
        assertEquals(keys.toSet(), reopened.keys("").toSet())
        assertEquals(keys.take(2).toSet(), reopened.keys("coding-log:").toSet())
        keys.forEachIndexed { index, key -> assertEquals("message-$index", reopened.read(key)) }
        reopened.write(keys.first(), "updated")
        assertEquals("updated", FileKeyValueStore(root).read(keys.first()))
        reopened.delete(keys.first())
        assertNull(FileKeyValueStore(root).read(keys.first()))
        assertEquals(keys.drop(1).toSet(), FileKeyValueStore(root).keys("").toSet())
        reopened.clear()
        assertTrue(FileKeyValueStore(root).keys("").isEmpty())
        keys.forEach { assertNull(FileKeyValueStore(root).read(it)) }
        assertTrue(root.listFiles().orEmpty().none { it.extension == "pending" || it.extension == "data" })
    }

    @Test
    fun existingNamesAtFilesystemLimitRemainReadableAndWritable() = withRoot { root ->
        val key = "a".repeat(250)
        val legacy = File(root, "$key.json")
        legacy.writeText("existing")
        val store = FileKeyValueStore(root)
        assertEquals("existing", store.read(key))
        assertEquals(listOf(key), store.keys("a"))
        store.write(key, "updated")
        assertEquals("updated", legacy.readText())
        assertEquals("updated", FileKeyValueStore(root).read(key))
        assertTrue(root.listFiles().orEmpty().none { it.extension == "pending" })
    }

    @Test
    fun batchReadMatchesSingleReadsIncludingMissingAndLongKeys() = withRoot { root ->
        val keys = (0 until 100).map { "payload:$it" } + ("long:" + "x".repeat(300))
        val store = FileKeyValueStore(root)
        keys.forEachIndexed { index, key -> if (index % 7 != 0) store.write(key, "value-$index") }
        val reopened = FileKeyValueStore(root)
        val requested = keys + "absent" + keys.first()
        assertEquals(requested.distinct().associateWith(reopened::read), reopened.readAll(requested))
        assertEquals(mapOf("payload:1" to "value-1"), reopened.readAll(listOf("payload:1")))
        assertTrue(reopened.readAll(emptyList()).isEmpty())
    }

    @Test
    fun identicalRewriteKeepsTheCommittedFileAndChangedValueReplacesIt() = withRoot { root ->
        val store = FileKeyValueStore(root)
        store.write("projection", "same")
        val committed = File(root, "projection.json").toPath()
        val identity = Files.readAttributes(committed, BasicFileAttributes::class.java).fileKey()
        store.write("projection", "same")
        if (identity != null) assertEquals(identity, Files.readAttributes(committed, BasicFileAttributes::class.java).fileKey())
        store.write("projection", "diff")
        assertEquals("diff", FileKeyValueStore(root).read("projection"))
        if (identity != null) assertNotEquals(identity, Files.readAttributes(committed, BasicFileAttributes::class.java).fileKey())
        // A key missing from the manifest is still recorded when its bytes are already on disk.
        File(root, "adopted.json").writeText("value")
        store.write("adopted", "value")
        assertTrue("adopted" in File(root, "manifest.txt").readLines())
        assertTrue(root.listFiles().orEmpty().none { it.extension == "pending" })
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("magicpaper-store-test-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
