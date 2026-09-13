package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface FilePicker {
    /** Доступен ли выбор файлов на этой платформе. */
    val supported: Boolean

    /**
     * Максимальный размер одного файла: на браузере жёстче из-за
     * квоты localStorage, на десктопе свободнее.
     */
    val maxFileBytes: Long

    /** Пустой список — пользователь отменил выбор. */
    suspend fun pickFiles(): List<PickedFile>

    /** Снимок вложений буфера; null оставляет стандартную вставку текста. */
    fun clipboardFiles(): (suspend () -> List<PickedFile>)? = null
}