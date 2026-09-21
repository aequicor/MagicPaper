package io.aequicor.magicpaper.data.coding

import java.io.File
import java.net.URL
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pi ищет `fd` (инструмент `find`) и `rg` (`grep`) в каталоге конфига, отдельном на
 * каждую сессию, а с `PI_OFFLINE=1` не скачивает отсутствующее. Приложение держит
 * бинарники дистрибутива в общем `coding/bin` и добавляет его в PATH процесса.
 */
class PiCodingSearchToolsTest {
    @Test
    fun `bundled binaries land in the shared bin dir`() {
        val root = createTempDirectory("coding-tools").toFile()
        try {
            val runtime = testInstallation(root)
            val fd = runtime.searchToolFileName("fd")
            val rg = runtime.searchToolFileName("rg")
            val bundle = mapOf(
                fd to "fd-bytes".toByteArray(),
                rg to "rg-bytes".toByteArray(),
                NOTICES to "licenses".toByteArray(),
            )
            assertEquals(
                listOf("fd", "rg"),
                runtime.installBundledSearchTools { name -> bundle[name] },
            )
            assertContentEquals("fd-bytes".toByteArray(), File(root, "bin/$fd").readBytes())
            assertContentEquals("rg-bytes".toByteArray(), File(root, "bin/$rg").readBytes())
            assertTrue(File(root, "bin/$NOTICES").isFile)
            // Оба бинарника на месте — предупреждение в статусе движка не нужно.
            assertTrue(runtime.searchToolsNotice().isBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reinstall refreshes only changed content`() {
        val root = createTempDirectory("coding-tools").toFile()
        try {
            val runtime = testInstallation(root)
            val fd = runtime.searchToolFileName("fd")
            val rg = runtime.searchToolFileName("rg")
            val first = mapOf(fd to "fd-1", rg to "rg-1").mapValues { it.value.toByteArray() }
            runtime.installBundledSearchTools { name -> first[name] }
            val second = mapOf(fd to "fd-2", rg to "rg-1").mapValues { it.value.toByteArray() }
            assertEquals(
                listOf("fd", "rg"),
                runtime.installBundledSearchTools { name -> second[name] },
            )
            assertContentEquals("fd-2".toByteArray(), File(root, "bin/$fd").readBytes())
            assertContentEquals("rg-1".toByteArray(), File(root, "bin/$rg").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `pi process PATH starts with the shared bin dir`() {
        val root = createTempDirectory("coding-tools").toFile()
        try {
            val runtime = testInstallation(root)
            runtime.installBundledSearchTools { name -> "bytes".toByteArray() }
            val env = runtime.piEnv(File(root, "node/node.exe"), File(root, "home"))
            assertEquals(File(root, "bin").absolutePath, env.getValue("PATH").split(File.pathSeparator).first())
            assertEquals("1", env.getValue("PI_OFFLINE"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing bundle keeps system path and never fails`() {
        val root = createTempDirectory("coding-tools").toFile()
        try {
            val runtime = testInstallation(root)
            assertTrue(runtime.installBundledSearchTools { null }.isEmpty())
            assertFalse(File(root, "bin").exists())
            // Бинарники могут быть доступны системным PATH — сверяем согласованность статуса.
            val available = runtime.searchToolNames.all { runtime.searchToolAvailable(it) }
            assertEquals(available, runtime.searchToolsNotice().isBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val NOTICES = "THIRD-PARTY-NOTICES.txt"
        const val TOOLS_TARGET = "target.txt"
    }
}
