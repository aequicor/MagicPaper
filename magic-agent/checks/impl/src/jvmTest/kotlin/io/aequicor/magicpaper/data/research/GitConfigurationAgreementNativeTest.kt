package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.DefaultCommandChecks
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Настоящая ОС: git и штатный драйвер проверок.
 *
 * Приложение обязано видеть то же состояние репозитория, что и git пользователя. Системный и
 * глобальный конфиг решают преобразование окончаний строк (`core.autocrlf`, `core.eol`), файл
 * атрибутов и фильтры, поэтому их отключение (`GIT_CONFIG_NOSYSTEM`, `GIT_CONFIG_GLOBAL`) делало
 * CRLF-чекаут при LF-индексе «незакоммиченными изменениями»: как только кэш stat переставал
 * совпадать, git без нормализации сравнивал байты, и worktree-режим отказывал при чистом, по мнению
 * пользователя, репозитории. Закалённые ключи (`core.fsmonitor`, редакторы, подпись, транспорт)
 * проверяет `OwnedGitMetadataPolicyTest`.
 */
class GitConfigurationAgreementNativeTest {
    private val enabled get() = System.getProperty("magicpaper.research.native") == "true"

    @Test fun readOnlyStatusReportsTheSameStateAsTheUsersGit() = runBlocking {
        assumeTrue(enabled)
        val root = Files.createTempDirectory("git-config-agreement-")
        val project = Files.createDirectory(root.resolve("project"))
        try {
            git(project, "init", "-q", "-b", "main")
            git(project, "config", "user.name", "Fixture")
            git(project, "config", "user.email", "fixture@example.test")
            val source = project.resolve("source.txt")
            Files.writeString(source, "first\nsecond\n")
            git(project, "add", ".")
            git(project, "commit", "-q", "-m", "base")
            // Fresh checkout applies the developer's own conversion; the index keeps the blob bytes.
            Files.delete(source)
            git(project, "checkout", "--", "source.txt")
            // Git compares content only for an entry whose cached stat data no longer matches.
            Files.setLastModifiedTime(source, FileTime.from(Instant.now().plusSeconds(2)))

            val driver = SandboxCheckDriver(root.resolve("runtime"), 120_000)
            val owner = DefaultCommandChecks(InMemoryEventJournal(), InMemoryKeyValueStore(), driver)
            try {
                val query = CheckCommand(CheckRef(CheckScope("agreement", "s", UUID.randomUUID().toString(), 0), "status"),
                    project.toString(), CheckGitReadQuery.STATUS_ALL.arguments(),
                    policy = CheckPolicy.GIT_READ_ONLY, outputMode = CheckOutputMode.BINARY_STDOUT)
                val result = owner.run(query)
                assertEquals(0, result.exitCode, result.toString())
                // The read-only query takes no lock, so the user's verdict is measured on the same state.
                val reported = owner.readOutput(query.ref).decodeToString()
                val expected = git(project, "status", "--porcelain", "--untracked-files=all")
                assertEquals(expected.trim(), reported.trim(),
                    "Приложение и git пользователя разошлись в состоянии репозитория")
                if (Files.readString(source).contains('\r'))
                    assertTrue(reported.isBlank(), "Преобразованный чекаут принят за изменение: $reported")
            } finally { owner.close() }
        } finally { root.toFile().deleteRecursively() }
    }

    private fun git(cwd: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "--no-pager") + args).directory(cwd.toFile())
            .redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), output)
        return output
    }
}
