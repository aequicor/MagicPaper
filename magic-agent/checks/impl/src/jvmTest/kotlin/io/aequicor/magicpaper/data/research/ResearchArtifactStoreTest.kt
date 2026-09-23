package io.aequicor.magicpaper.data.research

import java.nio.file.Files
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.*

class ResearchArtifactStoreTest {
    @Test fun corruptedManifestIsRetainedAndNeverBecomesEmptyPermissionState() {
        val root = Files.createTempDirectory("artifact-corruption-")
        val project = Files.createTempDirectory("artifact-project-").toRealPath()
        try {
            val manifest = root.resolve("artifacts-${ResearchArtifactStore.key(project.toString())}.json")
            Files.writeString(manifest, "corrupted")
            assertFails { ResearchArtifactStore(root).read(project) }
            assertEquals("corrupted", Files.readString(manifest))
        } finally { root.toFile().deleteRecursively(); project.toFile().deleteRecursively() }
    }

    @Test fun manifestCannotGrantAnOutsidePathOrMalformedDigest() {
        val root = Files.createTempDirectory("artifact-scope-")
        val project = Files.createTempDirectory("artifact-project-").toRealPath()
        try {
            val manifest = root.resolve("artifacts-${ResearchArtifactStore.key(project.toString())}.json")
            // A Windows path is not valid JSON on its own: encoding it keeps this a rejection of the
            // malformed digest rather than a failure to parse the manifest at all.
            fun write(entries: Map<String, String>) = Files.writeString(manifest,
                Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), entries))
            write(mapOf("/outside/file" to "a".repeat(64)))
            assertFailsWith<IllegalStateException> { ResearchArtifactStore(root).read(project) }
            // A Windows path carries backslashes: interpolate it through the JSON writer, or the
            // manifest is unparseable and the test proves the decoder instead of the digest rule.
            write(mapOf(project.resolve("output").toString() to "not-a-digest"))
            assertFailsWith<IllegalStateException> { ResearchArtifactStore(root).read(project) }
        } finally { root.toFile().deleteRecursively(); project.toFile().deleteRecursively() }
    }

    @Test fun attestationBindsExactFilesAndRejectsChangedPriorManifest() {
        val root = Files.createTempDirectory("artifact-attestation-")
        val project = Files.createTempDirectory("artifact-project-").toRealPath()
        try {
            val output = Files.createDirectory(project.resolve("build"))
            val file = Files.writeString(output.resolve("result.txt"), "first")
            val store = ResearchArtifactStore(root)
            val before = store.read(project)
            val proof = store.commit(before, listOf(output), "receipt-1")
            assertEquals(64, proof.length)
            assertEquals(ResearchArtifactStore.digest("first"), store.read(project).files[file])
            Files.writeString(file, "second")
            assertFailsWith<IllegalStateException> { store.commit(before, listOf(output), "receipt-2") }
            assertEquals(ResearchArtifactStore.digest("first"), store.read(project).files[file])
        } finally { root.toFile().deleteRecursively(); project.toFile().deleteRecursively() }
    }
}
