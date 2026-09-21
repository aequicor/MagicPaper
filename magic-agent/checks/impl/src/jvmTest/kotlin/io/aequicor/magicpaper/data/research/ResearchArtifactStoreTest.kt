package io.aequicor.magicpaper.data.research

import java.nio.file.Files
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
            Files.writeString(manifest, "{\"/outside/file\":\"${"a".repeat(64)}\"}")
            assertFailsWith<IllegalStateException> { ResearchArtifactStore(root).read(project) }
            Files.writeString(manifest, "{\"${project.resolve("output")}\":\"not-a-digest\"}")
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
