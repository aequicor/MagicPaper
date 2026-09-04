package io.aequicor.magicpaper.plugins.builtin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.plugins.MagicPlugin

/** Плагин «Счёты»: безопасный вычислитель простых выражений. */
object CalcPlugin : MagicPlugin {
    override val id = "calc"
    override val title = "Счёты"
    override val description = "Простой калькулятор выражений."
    override val icon = "∑"

    @Composable
    override fun Content() {
        var expr by remember { mutableStateOf("") }
        val result = remember(expr) {
            if (expr.isBlank()) "" else " = " + evaluate(expr).getOrElse { "ошибка" }
        }
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(icon + " " + title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row {
                OutlinedTextField(
                    value = expr,
                    onValueChange = { expr = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Например: 2 + 2 * 3") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Text(result, style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    /** Мини-парсер выражений: числа, + - * / и скобки. Без внешних библиотек. */
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
