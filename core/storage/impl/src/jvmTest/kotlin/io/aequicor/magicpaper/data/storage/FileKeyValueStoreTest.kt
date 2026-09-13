package io.aequicor.magicpaper.data.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("magicpaper-store-test-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
