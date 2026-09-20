package io.aequicor.magicpaper.data.coding

import java.io.File
import java.io.IOException

/**
 * Выбор исполняемого файла для проверок, которые приложение запускает без пользовательской оболочки.
 *
 * Windows не применяет PATHEXT к полному пути: CreateProcess отказывает файлу без исполнимого расширения
 * (error=193), если это не PE-образ. Репозитории же хранят обе оболочки обёртки Gradle — POSIX-сценарий
 * `gradlew` и `gradlew.bat`, — поэтому записанная агентом команда `./gradlew` должна стартовать обёрткой
 * текущей платформы, а не падать с кодом CreateProcess. Правила общие для итоговых проверок рабочей копии
 * задачи (TaskWorktreeIntegrationChecks) и защищённых проверок исследования (ResearchCheckRunner).
 */
internal object WindowsExecutables {
    private val windows by lazy { System.getProperty("os.name").startsWith("Windows") }

    fun isWindows() = windows

    /** Расширения исполнимых файлов из окружения; пустой список означает, что источник недоступен. */
    fun extensions(pathExt: String?): List<String> = pathExt.orEmpty().split(';').map { it.trim() }.filter { it.isNotBlank() }

    /**
     * Порядок перебора суффиксов для имени команды. Имя, уже заканчивающееся известным расширением,
     * принимается как есть; иначе на Windows исполнимые варианты проверяются раньше «голого» имени.
     */
    fun suffixes(name: String, extensions: List<String>): List<String> {
        val known = extensions.filter { it.isNotBlank() }
        return when {
            !windows || known.isEmpty() -> listOf("")
            known.any { name.endsWith(it, ignoreCase = true) } -> listOf("") + known
            else -> known + ""
        }
    }

    /** Файл запускается на Windows: расширение из PATHEXT либо PE-образ, заданный полным путём. */
    fun isLaunchable(file: File, extensions: List<String>): Boolean {
        val known = extensions.filter { it.isNotBlank() }
        return !windows || known.isEmpty() || known.any { file.name.endsWith(it, ignoreCase = true) } || isPeImage(file)
    }

    private fun isPeImage(file: File) = try {
        file.inputStream().use { input ->
            val magic = input.readNBytes(2)
            magic.size == 2 && magic[0] == 'M'.code.toByte() && magic[1] == 'Z'.code.toByte()
        }
    } catch (failure: IOException) {
        // Нечитаемый кандидат не считается исполнимым: запуск всё равно завершился бы отказом ОС.
        false
    }
}
