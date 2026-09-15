package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.SessionIntegrationCheck
import io.aequicor.magicpaper.domain.SessionIntegrationCheckRunner
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Итоговые проверки управляемой рабочей копии задачи.
 *
 * Worktree задачи — одноразовая копия, принадлежащая приложению: во время задачи сессия уже выполняла
 * те же команды своими обычными инструментами, а в целевую ветку доставляется только проверенный
 * merge-коммит; целостность охраняют снимок до и после проверок (verificationSnapshot) и ff-only
 * доставка. Поэтому здесь не применяется песочница записи ОС, используемая для исследовательских
 * проверок живого пользовательского проекта (ResearchSessionIntegrationChecks): сборочным инструментам
 * нужны запись в собственные каталоги результатов и тёплые кеши разработчика, а политика исследования
 * изымает каталоги с существующими файлами и опустошает кеши, из-за чего такие проверки не могут пройти.
 *
 * Жизненный цикл проверки принадлежит этому раннеру: предел вывода, таймаут, убийство дерева процессов
 * при остановке или отмене корутины. Отмена остаётся управлением потоком и propagates как CancellationException.
 */
class TaskWorktreeIntegrationChecks(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : SessionIntegrationCheckRunner {
    private val active = ConcurrentHashMap<String, Process>()

    override suspend fun run(path: String, id: String, command: List<String>): SessionIntegrationCheck {
        require(command.isNotEmpty() && command.size <= 128 && command.all { it.length <= 16_384 && '\u0000' !in it }) { "Нужны команда и корректные аргументы" }
        val dir = File(path).canonicalFile
        require(dir.isDirectory) { "Рабочая копия задачи недоступна" }
        return withContext(Dispatchers.IO) {
            val output = File.createTempFile("magicpaper-task-check-", ".log")
            var process: Process? = null
            var registered = false
            try {
                currentCoroutineContext().ensureActive()
                val started = ProcessBuilder(resolve(dir, command)).directory(dir).redirectErrorStream(true).redirectOutput(output).start()
                process = started
                started.outputStream.close()
                check(active.putIfAbsent(id, started) == null) { "Проверка этой задачи уже выполняется" }
                registered = true
                val timedOut = try {
                    withTimeoutOrNull(timeoutMillis) {
                        while (started.isAlive) { currentCoroutineContext().ensureActive(); delay(50) }
                        false
                    } ?: true
                } catch (cancelled: CancellationException) {
                    kill(started); throw cancelled
                }
                if (timedOut) kill(started)
                val text = tail(output)
                if (timedOut) SessionIntegrationCheck(command, null, text + "\n$TIMEOUT_NOTE", TIMEOUT_NOTE)
                else SessionIntegrationCheck(command, started.exitValue(), text, null)
            } catch (cancelled: CancellationException) {
                process?.let { kill(it) }
                throw cancelled
            } catch (failure: Exception) {
                // Недоступность проверки — её исход: владелец приёмки сообщит о сбое, а не повторит его молча.
                SessionIntegrationCheck(command, null, "", "Проверка недоступна: ${failure.message}")
            } finally {
                if (registered) active.remove(id, process)
                withContext(NonCancellable) { process?.let { if (it.isAlive) kill(it) } }
                output.delete()
            }
        }
    }

    override fun abort(id: String) { active[id]?.let(::kill) }

    /** Проверка не оставляет служебных каталогов и владеющих процессов: только собственные build-выходы worktree. */
    override suspend fun reconcile(id: String) { active.remove(id) }

    /**
     * CreateProcess ищет относительный исполняемый файл в каталоге родительского процесса, а не в каталоге
     * проверки, поэтому путь с разделителем закрепляется за рабочей копией; простое имя остаётся на поиск PATH.
     */
    private fun resolve(dir: File, command: List<String>): List<String> {
        val name = command.first()
        val relative = name.contains('/') || name.contains(File.separatorChar)
        if (name.isBlank() || !relative && !File(dir, name).isFile) return command
        val suffixes = listOf("") + System.getenv("PATHEXT").orEmpty().split(';').filter { it.isNotBlank() }
        val file = suffixes.asSequence().map { File(dir, name + it) }.firstOrNull { it.isFile } ?: return command
        return listOf(file.canonicalPath) + command.drop(1)
    }

    private fun kill(process: Process) {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        process.waitFor()
    }

    private fun tail(output: File): String = output.inputStream().use { stream ->
        val size = output.length()
        if (size > MAX_OUTPUT) {
            var skipped = 0L
            while (skipped < size - MAX_OUTPUT) {
                val step = stream.skip(size - MAX_OUTPUT - skipped)
                if (step <= 0) break
                skipped += step
            }
        }
        stream.readBytes().toString(Charsets.UTF_8)
    }

    companion object {
        const val MAX_OUTPUT = 64_000
        const val DEFAULT_TIMEOUT_MILLIS = 15 * 60_000L
        const val TIMEOUT_NOTE = "Проверка остановлена по таймауту"
    }
}
