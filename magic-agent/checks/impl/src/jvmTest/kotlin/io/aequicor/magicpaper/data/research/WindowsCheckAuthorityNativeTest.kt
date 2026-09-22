package io.aequicor.magicpaper.data.research

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Реальный NTFS без opt-in флага нативных проверок: тест не запускает ни одного процесса и трогает
 * только каталог, который сам создал и удалил. Точное восстановление ACL — единственное доказательство
 * того, что проверка не оставила следов в рабочей папке пользователя, поэтому оно проверяется на каждом
 * прогоне на Windows, а не только в opt-in наборе.
 *
 * Регрессия: `SetSecurityInfo` пересобирает дескриптор из одного DACL и всегда проставляет
 * `SE_DACL_AUTO_INHERITED` на незащищённой записи, поэтому исходный ACL без этого флага не возвращался
 * никогда — байты ACE совпадали, а SDDL читался как `D:AI(...)`, и доказательство восстановления
 * отклонялось. Без доказательства `restoreAndConfirm` ронял любую защищённую проверку, probe
 * фиксировал `probeFailure`, и все Git-чтения визита отвечали «Завершение проверки не подтверждено».
 */
class WindowsCheckAuthorityNativeTest {
    @Test fun grantAndRestoreReturnsEveryOriginalAclByteIdentical() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("check-authority-")
        try {
            assumeTrue(Files.getFileStore(root).type().equals("NTFS", true))
            val artifacts = Files.createDirectory(root.resolve("build"))
            val nested = Files.createDirectory(artifacts.resolve("nested"))
            Files.writeString(artifacts.resolve("file.txt"), "current")
            Files.writeString(nested.resolve("deep.txt"), "current")
            val scratch = Files.createDirectory(root.resolve("scratch"))

            val uuid = UUID.randomUUID()
            val sid = "S-1-5-21-${uuid.mostSignificantBits.toUInt()}-" +
                "${(uuid.mostSignificantBits ushr 32).toUInt()}-${uuid.leastSignificantBits.toUInt()}-1031"
            val authority = WindowsCheckAuthority.capture(listOf(artifacts, scratch), sid, "receipt-1") { id, _ -> id }
            try {
                authority.grant(sid)
                assertEquals("acl-restored:receipt-1", authority.restoreAndConfirm())
            } finally { authority.close() }
        } finally { root.toFile().deleteRecursively() }
    }

    /**
     * Файлы, которые команда создала в каталоге результатов, наследуют ACE временного SID и не имеют
     * снимка, к которому можно вернуться. Без снятия этого ACE доказательство восстановления не выдавалось
     * никогда, хотя исходные ACL уже были возвращены байт в байт.
     */
    @Test fun restoreRevokesRunAuthorityFromArtifactsTheCommandCreated() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("check-authority-artifacts-")
        try {
            assumeTrue(Files.getFileStore(root).type().equals("NTFS", true))
            val artifacts = Files.createDirectory(root.resolve("build"))
            Files.writeString(artifacts.resolve("before.txt"), "current")
            val scratch = Files.createDirectory(root.resolve("scratch"))

            val uuid = UUID.randomUUID()
            val sid = "S-1-5-21-${uuid.mostSignificantBits.toUInt()}-" +
                "${(uuid.mostSignificantBits ushr 32).toUInt()}-${uuid.leastSignificantBits.toUInt()}-1031"
            val authority = WindowsCheckAuthority.capture(listOf(artifacts, scratch), sid, "receipt-2") { id, _ -> id }
            try {
                authority.grant(sid)
                // Stands in for the sandboxed command: new objects inherit the granted run ACE.
                Files.writeString(artifacts.resolve("created.txt"), "artifact")
                val generated = Files.createDirectory(artifacts.resolve("generated"))
                Files.writeString(generated.resolve("deep.txt"), "artifact")
                Files.writeString(scratch.resolve("temporary.txt"), "artifact")
                assertEquals("acl-restored:receipt-2", authority.restoreAndConfirm())
            } finally { authority.close() }
        } finally { root.toFile().deleteRecursively() }
    }
}
