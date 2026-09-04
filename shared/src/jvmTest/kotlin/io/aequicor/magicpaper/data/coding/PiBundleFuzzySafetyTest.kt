package io.aequicor.magicpaper.data.coding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверка защиты кодировки: патчер бандла должен переписать ВСЕ копии
 * normalizeForFuzzyMatch (esbuild клонирует их с суффиксами), остаться
 * идемпотентным и не тронуть остальной код чанка.
 */
class PiBundleFuzzySafetyTest {

    private fun tempRoot(): File = kotlin.io.path.createTempDirectory("mp-fuzzy").toFile()

    private fun chunkFile(root: File): File {
        val chunks = File(root, "prefix/node_modules/@earendil-works/pi-coding-agent/dist/bundle/chunks")
        chunks.mkdirs()
        return File(chunks, "chunk-TEST.js")
    }

    /** Синтетический чанк в стиле esbuild: две копии функции + соседи. */
    private val synthetic =
        "function detectLineEnding(c){return 1}" +
            "function normalizeForFuzzyMatch(text){return text.normalize(\"NFKC\").replace(/[\\u2014]/g,\"-\")}" +
            "function splitLinesWithEndings(content){return 2}" +
            "function normalizeForFuzzyMatch2(text){return text.normalize(\"NFKC\").replace(/[\\u2014]/g,\"-\")}" +
            "function fuzzyFindText(content,oldText){return 3}"

    @Test
    fun `patches every copy and is idempotent`() {
        val root = tempRoot()
        try {
            val chunk = chunkFile(root)
            chunk.writeText(synthetic, Charsets.UTF_8)
            val runtime = PiCodingRuntime(rootDir = root)

            runtime.patchBundleFuzzySafety()
            val once = chunk.readText(Charsets.UTF_8)

            assertEquals(2, Regex(Regex.escape("magicpaper-fuzzy-safety")).findAll(once).count())
            assertTrue(
                "function normalizeForFuzzyMatch(text){return /*magicpaper-fuzzy-safety*/" in once,
                "первая копия не пропатчена",
            )
            assertTrue(
                "function normalizeForFuzzyMatch2(text){return /*magicpaper-fuzzy-safety*/" in once,
                "вторая копия не пропатчена",
            )
            // Опасных нормализаций (NFKC и замен типографики) не осталось.
            assertEquals(0, Regex("NFKC").findAll(once).count())
            // Соседи не тронуты.
            assertTrue("function detectLineEnding" in once)
            assertTrue("function splitLinesWithEndings" in once)
            assertTrue("function fuzzyFindText(content,oldText){return 3}" in once)

            // Идемпотентность: второй проход не меняет файл.
            runtime.patchBundleFuzzySafety()
            assertEquals(once, chunk.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `chunk without the function is untouched`() {
        val root = tempRoot()
        try {
            val chunk = chunkFile(root)
            val text = "function other(x){return x}"
            chunk.writeText(text, Charsets.UTF_8)
            PiCodingRuntime(rootDir = root).patchBundleFuzzySafety()
            assertEquals(text, chunk.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }
}
