package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.designsystem.*

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.plugins.MagicPlugin
import kotlinx.coroutines.delay

/** Плагин «Фокус»: таймер концентрации 25 минут. */
object FocusPlugin : MagicPlugin {
    override val id = "focus"
    override val title = "Фокус"
    override val description = "Таймер концентрации на 25 минут."
    override val icon = "◷"

    private const val TOTAL_SECONDS = 25 * 60

    @Composable
    override fun Content() {
        var remaining by remember { mutableStateOf(TOTAL_SECONDS) }
        var running by remember { mutableStateOf(false) }

        LaunchedEffect(running) {
            while (running && remaining > 0) {
                delay(1000)
                remaining -= 1
            }
            if (remaining == 0) running = false
        }

        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            PaperText(icon + " " + title, style = LocalPaperTypography.current.title)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PaperText(
                    format(remaining),
                    style = LocalPaperTypography.current.headline,
                    color = LocalPaperColors.current.action,
                )
                Spacer(Modifier.width(12.dp))
                PaperAction(onClick = { running = !running }) {
                    PaperText(if (running) "Пауза" else "Старт")
                }
                PaperAction(onClick = {
                    running = false
                    remaining = TOTAL_SECONDS
                }) {
                    PaperText("Сброс")
                }
            }
        }
    }

    private fun format(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }
}
