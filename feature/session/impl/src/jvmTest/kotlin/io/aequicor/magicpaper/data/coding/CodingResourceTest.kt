package io.aequicor.magicpaper.data.coding

import java.io.File
import java.net.URI
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import java.util.zip.ZipException

class CodingResourceTest {
    @Test
    fun `reads rebuilt jar without retaining old entry offsets`() {
        val root = createTempDirectory("coding-resource").toFile()
        try {
            val jar = File(root, "runtime.jar")
            fun writeJar(padding: String, script: String) {
                JarOutputStream(jar.outputStream()).use { output ->
                    for ((name, content) in listOf("padding" to padding, "coding/test.mjs" to script)) {
                        output.putNextEntry(JarEntry(name))
                        output.write(content.toByteArray())
                        output.closeEntry()
                    }
                }
            }
            val resource = URI("jar:${jar.toURI().toURL()}!/coding/test.mjs").toURL()
            writeJar("short", "export const version = 1;")
            assertEquals("export const version = 1;", readCodingResource(resource).decodeToString())
            writeJar((0..1000).joinToString(), "export const version = 2;")
            assertEquals("export const version = 2;", readCodingResource(resource).decodeToString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `corrupt jar preserves cause with safe recovery message`() {
        val root = createTempDirectory("coding-resource").toFile()
        try {
            val jar = File(root, "private-path.jar").apply { writeText("broken archive") }
            val resource = URI("jar:${jar.toURI().toURL()}!/coding/test.mjs").toURL()
            val failure = assertFailsWith<CodingResourceException> { readCodingResource(resource) }
            assertIs<ZipException>(failure.cause)
            assertFalse(checkNotNull(failure.message).contains("private-path"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `extracts bundled adapter to stable content addressed path`() {
        val root = createTempDirectory("coding-resource").toFile()
        try {
            val runtime = PiCodingRuntime(rootDir = root)
            val script = runtime.resourceScript("usage-context.mjs")
            val expected = javaClass.getResourceAsStream("/coding/usage-context.mjs")!!.use { it.readBytes() }
            assertEquals(expected.decodeToString(), script.readText())
            assertEquals(script, runtime.resourceScript("usage-context.mjs"))
        } finally {
            root.deleteRecursively()
        }
    }
}
