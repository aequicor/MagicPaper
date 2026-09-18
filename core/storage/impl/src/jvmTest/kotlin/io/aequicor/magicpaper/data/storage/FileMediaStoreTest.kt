package io.aequicor.magicpaper.data.storage

import java.nio.file.Files
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class FileMediaStoreTest {
    private val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j2ioAAAAASUVORK5CYII=")

    @Test fun assetsAndOperationsReopenWithoutEmbeddingBytesInRecords() = runTest {
        val root = Files.createTempDirectory("paper-media-test").toFile()
        try {
            val first = FileMediaStore(root)
            val asset = first.put(png, "image/png", 1, 1)
            first.writeRecord("operation:one", "{\"assetId\":\"${asset.id}\"}")
            val reopened = FileMediaStore(root)
            assertContentEquals(png, reopened.read(asset))
            assertTrue(File(reopened.localPath(asset)).isFile)
            assertEquals(first.readRecord("operation:one"), reopened.records("operation:")["operation:one"])
            assertFalse(reopened.readRecord("operation:one")!!.contains(Base64.encode(png)))
            assertEquals(asset, reopened.put(png, "image/png", 1, 1))
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptAndMissingAssetsAreExplicitFailures() = runTest {
        val root = Files.createTempDirectory("paper-media-test").toFile()
        try {
            val store = FileMediaStore(root)
            val asset = store.put(png, "image/png", 1, 1)
            assertFailsWith<StorageException> { store.read(asset.copy(byteSize = asset.byteSize + 1)) }
            val file = File(store.localPath(asset))
            file.writeBytes(png.copyOf().also { it[it.lastIndex] = 0 })
            assertFailsWith<StorageException> { store.read(asset) }
            assertFailsWith<StorageException> { store.localPath(asset) }
            file.delete()
            assertFailsWith<StorageException> { store.read(asset) }
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsPayloadTypeAndPathTraversal() = runTest {
        val root = Files.createTempDirectory("paper-media-test").toFile()
        try {
            val store = FileMediaStore(root)
            assertFailsWith<StorageException> { store.put("<html>error</html>".encodeToByteArray(), "image/png") }
            assertFailsWith<StorageException> { store.put(png, "video/mp4") }
            val asset = store.put(png, "image/png")
            assertFailsWith<StorageException> { store.localPath(asset.copy(id = "../escape")) }
            store.clear()
            assertNull(store.readRecord("missing"))
            assertFailsWith<StorageException> { store.read(asset) }
        } finally { root.deleteRecursively() }
    }
}
