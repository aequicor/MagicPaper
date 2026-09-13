package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.designsystem.*

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import io.aequicor.magicpaper.data.storage.DraftRepository
import io.aequicor.magicpaper.data.storage.PersistentDraftValue
import io.aequicor.magicpaper.plugins.PersistentPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.builtins.serializer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.plugins.MagicPlugin

/** Плагин «Счёты»: безопасный вычислитель простых выражений. */
class CalcPlugin(repository: DraftRepository, applicationScope: CoroutineScope) : MagicPlugin, PersistentPlugin {
    private val owner = PersistentDraftValue(repository, "plugin:calc", String.serializer(), "", applicationScope)
    override suspend fun flushDrafts() = owner.flushDrafts()
    override suspend fun prepareForReset() = owner.prepareForReset()
    override fun resumeAfterReset() = owner.resumeAfterReset()
    override val id = "calc"
    override val title = "Счёты"
    override val description = "Простой калькулятор выражений."
    override val icon = "∑"

    @Composable
    override fun Content() {
        val draft by owner.draft.state.collectAsState()
        val expr = draft.value
        val result = remember(expr) {
            if (expr.isBlank()) "" else " = " + evaluate(expr).getOrElse { "ошибка" }
        }
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            PaperText(icon + " " + title, style = LocalPaperTypography.current.title)
            if (draft.error != null) PaperText("Не удалось сохранить выражение.", color = LocalPaperColors.current.error)
            Spacer(Modifier.height(6.dp))
            Row {
                PaperInput(
                    value = expr,
                    onValueChange = { value -> owner.update { value } },
                    enabled = draft.loaded,
                    modifier = Modifier.weight(1f),
                    placeholder = { PaperText("Например: 2 + 2 * 3") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                PaperText(result, style = LocalPaperTypography.current.title)
            }
        }
    }

    /** Мини-парсер выражений: числа, + - * / и скобки. Без внешних библиотек. */
    companion object {
    fun evaluate(input: String): Result<Double> = runCatching {
        val parser = Parser(input.replace(" ", ""))
        val value = parser.parseExpr()
        require(parser.done()) { "Лишние символы" }
        value
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun done() = pos == s.length

        fun parseExpr(): Double {
            var value = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; value *= parseFactor() }
                    '/' -> {
                        pos++
                        val d = parseFactor()
                        require(d != 0.0) { "Деление на ноль" }
                        value /= d
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            val c = peek() ?: throw IllegalArgumentException("Неожиданный конец")
            if (c == '(') {
                pos++
                val v = parseExpr()
                require(peek() == ')') { "Не хватает )" }
                pos++
                return v
            }
            if (c == '-') {
                pos++
                return -parseFactor()
            }
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            require(pos > start) { "Ожидалось число" }
            return s.substring(start, pos).toDouble()
        }

        private fun peek(): Char? = if (pos < s.length) s[pos] else null
    }
}
}
