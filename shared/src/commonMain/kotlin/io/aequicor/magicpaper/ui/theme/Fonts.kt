package io.aequicor.magicpaper.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.aequicor.magicpaper.resources.Res
import io.aequicor.magicpaper.resources.cormorant_garamond_bold
import io.aequicor.magicpaper.resources.cormorant_garamond_medium
import io.aequicor.magicpaper.resources.cormorant_garamond_semibold
import io.aequicor.magicpaper.resources.jetbrains_mono_medium
import io.aequicor.magicpaper.resources.jetbrains_mono_regular
import io.aequicor.magicpaper.resources.literata_italic
import io.aequicor.magicpaper.resources.literata_medium
import io.aequicor.magicpaper.resources.literata_regular
import io.aequicor.magicpaper.resources.literata_semibold
import org.jetbrains.compose.resources.Font

/**
 * Шрифтовой стек «магической бумаги» (все гарнитуры — OFL 1.1, см.
 * composeResources/font/OFL_ALL.txt). Встроен через Compose Resources,
 * поэтому одинаков на десктопе, Android и в браузере.
 *
 * Покрытие стека: латиница (вкл. расширенную), кириллица (вкл. расширения),
 * греческий, вьетнамский. Письменности вне покрытия (китайский, арабская…)
 * дорисовывает системный фолбэк платформы — поведение штатное.
 *
 * Загрузка ресурсов композиции — @Composable, поэтому семейства собираются
 * ленивыми свойствами и читаются из композиционного контекста; снаружи
 * типографики их стоит держать через `remember`.
 */
object MagicFonts {
    /**
     * Дисплейная гарнитура: бренд и крупные заголовки. Каллиграфический
     * гарамон с тонкими штрихами — читается только от ~17sp, в мелких
     * ролях не используется.
     */
    val display: FontFamily
        @Composable get() = FontFamily(
            Font(Res.font.cormorant_garamond_medium, FontWeight.Medium),
            Font(Res.font.cormorant_garamond_semibold, FontWeight.SemiBold),
            Font(Res.font.cormorant_garamond_bold, FontWeight.Bold),
        )

    /**
     * Текстовая гарнитура: всё, что читается долго (чат, доки, подписи).
     * Экранная серифа с полным кириллическим набором.
     */
    val text: FontFamily
        @Composable get() = FontFamily(
            Font(Res.font.literata_regular, FontWeight.Normal),
            Font(Res.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
            Font(Res.font.literata_medium, FontWeight.Medium),
            Font(Res.font.literata_semibold, FontWeight.SemiBold),
        )

    /** Моноширинная гарнитура: блоки кода, инлайн-код, технические значения. */
    val code: FontFamily
        @Composable get() = FontFamily(
            Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
            Font(Res.font.jetbrains_mono_medium, FontWeight.Medium),
        )
}
