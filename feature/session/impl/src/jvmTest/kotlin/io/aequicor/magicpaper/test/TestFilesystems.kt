package io.aequicor.magicpaper.test

import java.io.IOException
import java.nio.file.Files

/**
 * Сценарии «символьная ссылка не выводит запись за пределы папки» требуют, чтобы тест сам создавал
 * ссылки. На Windows без Developer Mode (и на файловых системах без поддержки ссылок) создание
 * падает по правам: такие среды пропускаются как неспособные воспроизвести сценарий, а не
 * маскируются успехом.
 */
fun assumeSymbolicLinksAvailable() {
    val probe = Files.createTempDirectory("magicpaper-link-probe")
    try {
        try {
            Files.createSymbolicLink(probe.resolve("link"), probe)
        } catch (unavailable: IOException) {
            org.junit.Assume.assumeTrue(
                "Создание символьных ссылок недоступно в этой среде (${unavailable.javaClass.simpleName})",
                false,
            )
        }
    } finally {
        Files.deleteIfExists(probe.resolve("link"))
        Files.deleteIfExists(probe)
    }
}
