package io.aequicor.magicpaper.data.research

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Реальный NTFS без opt-in флага нативных проверок: тест не запускает ни одного процесса и трогает
 * только каталог, который сам создал и удалил. Точное восстановление метки целостности — единственное
 * доказательство того, что проверка не оставила каталогам пользователя доступ на запись из песочницы,
 * поэтому оно проверяется на каждом прогоне на Windows, а не только в opt-in наборе.
 *
 * Регрессия: Windows пересчитывает флаги наследования при любой записи метки — дескриптор читается
 * обратно как `S:AI(...)`, а унаследованный ACE как `(...;ID;...)`, — поэтому побайтовое сравнение
 * SDDL делало восстановление недоказуемым. Без доказательства `restoreAndConfirm` ронял любую
 * защищённую проверку, probe фиксировал `probeFailure`, и все Git-чтения визита отвечали
 * «Завершение проверки не подтверждено».
 */
class WindowsCheckAuthorityNativeTest {
    @Test fun lowerAndRestoreReturnsEveryOriginalLabel() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("check-authority-")
        try {
            assumeTrue(Files.getFileStore(root).type().equals("NTFS", true))
            val artifacts = Files.createDirectory(root.resolve("build"))
            val nested = Files.createDirectory(artifacts.resolve("nested"))
            Files.writeString(artifacts.resolve("file.txt"), "current")
            Files.writeString(nested.resolve("deep.txt"), "current")
            val scratch = Files.createDirectory(root.resolve("scratch"))

            val authority = WindowsCheckAuthority.capture(listOf(artifacts, scratch), "receipt-1") { id, _ -> id }
            try {
                authority.lower()
                assertEquals("label-restored:receipt-1", authority.restoreAndConfirm())
            } finally { authority.close() }
        } finally { root.toFile().deleteRecursively() }
    }

    /**
     * Файлы, которые команда создала в каталоге результатов, наследуют метку Low и не имеют снимка,
     * к которому можно вернуться. Без явного снятия метки доказательство восстановления не выдавалось
     * никогда, хотя исходные метки уже были возвращены.
     */
    @Test fun restoreRemovesBorrowedLabelFromArtifactsTheCommandCreated() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("check-authority-artifacts-")
        try {
            assumeTrue(Files.getFileStore(root).type().equals("NTFS", true))
            val artifacts = Files.createDirectory(root.resolve("build"))
            Files.writeString(artifacts.resolve("before.txt"), "current")
            val scratch = Files.createDirectory(root.resolve("scratch"))

            val authority = WindowsCheckAuthority.capture(listOf(artifacts, scratch), "receipt-2") { id, _ -> id }
            try {
                authority.lower()
                // Stands in for the sandboxed command: new objects inherit the lowered label.
                Files.writeString(artifacts.resolve("created.txt"), "artifact")
                val generated = Files.createDirectory(artifacts.resolve("generated"))
                Files.writeString(generated.resolve("deep.txt"), "artifact")
                Files.writeString(scratch.resolve("temporary.txt"), "artifact")
                assertEquals("label-restored:receipt-2", authority.restoreAndConfirm())
            } finally { authority.close() }
        } finally { root.toFile().deleteRecursively() }
    }
}
