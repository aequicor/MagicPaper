package io.aequicor.magicpaper.util

import kotlin.random.Random

/** Текущее время в миллисекундах (актуалы на платформах). */
internal expect fun currentTimeMillis(): Long

/** Утилиты общего назначения. */
object Id {
    /** Компактный уникальный идентификатор без внешних библиотек. */
    fun new(): String {
        val time = currentTimeMillis().toString(36)
        val salt = buildString {
            repeat(8) { append(HEX[Random.nextInt(HEX.length)]) }
        }
        return "$time-$salt"
    }

    fun now(): Long = currentTimeMillis()

    /** UUID v4 без внешних библиотек — для стабильных внешних идентификаторов. */
    fun uuid(): String {
        fun block(n: Int) = buildString { repeat(n) { append(HEX[Random.nextInt(HEX.length)]) } }
        return "${block(8)}-${block(4)}-4${block(3)}-${"89ab"[Random.nextInt(4)]}${block(3)}-${block(12)}"
    }

    private const val HEX = "0123456789abcdef"
}
