package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Каталог моделей кодинг-движков для UI и планировщика. Держит последний удачный снимок
 * каждого движка; сетевой или процессный опрос идёт только в [refresh].
 */
interface CodingModelCatalog {
    /** Последние известные снимки. Движок без снимка в карте отсутствует. */
    val snapshots: StateFlow<Map<CodingEngine, CodingModelSnapshot>>

    /**
     * Опрашивает движок и, если ответ получен, заменяет снимок. При сбое прежний снимок
     * остаётся нетронутым и возвращается в [CodingModelRefresh.Failed.retained]:
     * пустой ответ движка не выдаётся за успех и не стирает каталог.
     */
    suspend fun refresh(engine: CodingEngine): CodingModelRefresh
}

sealed interface CodingModelRefresh {
    data class Refreshed(val snapshot: CodingModelSnapshot) : CodingModelRefresh

    /** Технические подробности сбоя пишет в лог владелец каталога; сюда они не попадают. */
    data class Failed(
        val engine: CodingEngine,
        val reason: CodingModelRefreshFailure,
        val retained: CodingModelSnapshot?,
    ) : CodingModelRefresh
}

enum class CodingModelRefreshFailure {
    /** У платформы нет процесса этого движка (веб, Android) либо он не подключён. */
    UNAVAILABLE,

    /** Движок не вернул ни одной модели: вероятно, не настроена авторизация. */
    EMPTY,

    /** Опрос завершился ошибкой; повторить можно позже. */
    FAILED,
}

/**
 * Граница к процессу движка: одна реализация на движок. Возвращает каталог как есть;
 * ошибки пробрасывает, отмену корутины не поглощает.
 */
fun interface CodingModelSource {
    suspend fun fetch(): List<CodingModel>
}
