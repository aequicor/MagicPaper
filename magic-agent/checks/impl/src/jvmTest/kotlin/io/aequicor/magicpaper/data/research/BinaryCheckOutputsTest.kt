package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.CheckOutputLimitExceeded
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.*

class BinaryCheckOutputsTest {
    @Test fun immutableArtifactRejectsChangedIdentityDigestAndBytes() {
        val root = Files.createTempDirectory("binary-artifact-").toRealPath()
        try {
            val source = Files.write(root.resolve("source"), byteArrayOf(0, -1, 10, 13, -64))
            val store = BinaryCheckOutputs(root.resolve("outputs"))
            val ref = store.save("receipt", source)
            assertContentEquals(Files.readAllBytes(source), store.read(ref))
            assertEquals(ref, store.save("receipt", source))
            assertFails { store.read(ref.copy(id = "other")) }
            assertFails { store.read(ref.copy(bytes = ref.bytes + 1)) }
            assertFails { store.read(ref.copy(digest = "0".repeat(64))) }
            Files.write(source, byteArrayOf(1, 2))
            assertFails { store.save("receipt", source) }
            val saved = Files.list(root.resolve("outputs")).use { it.findFirst().orElseThrow() }
            Files.write(saved, byteArrayOf(1, 2))
            assertFails { store.read(ref) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun oversizeSourceNeverProducesAnArtifact() {
        val root = Files.createTempDirectory("binary-artifact-size-").toRealPath()
        try {
            val source = Files.createFile(root.resolve("source"))
            RandomAccessFile(source.toFile(), "rw").use { it.setLength(BinaryCheckOutputs.LIMIT + 1) }
            assertFailsWith<CheckOutputLimitExceeded> { BinaryCheckOutputs(root.resolve("outputs")).save("receipt", source) }
            assertFalse(Files.exists(root.resolve("outputs")))
        } finally { root.toFile().deleteRecursively() }
    }
}
