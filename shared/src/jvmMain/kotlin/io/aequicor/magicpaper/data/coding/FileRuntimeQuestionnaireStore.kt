package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.FileKeyValueStore
import io.aequicor.magicpaper.domain.RuntimeQuestionnaireRecord
import io.aequicor.magicpaper.domain.RuntimeQuestionnaireStore
import java.io.File

/** Constructing or refusing a runtime must not create its filesystem state before preflight. */
internal class FileRuntimeQuestionnaireStore(private val directory: File) : RuntimeQuestionnaireStore {
    private val delegate by lazy { JsonRuntimeQuestionnaireStore(FileKeyValueStore(directory)) }
    override fun load(): List<RuntimeQuestionnaireRecord> = if (directory.isDirectory) delegate.load() else emptyList()
    override fun save(records: List<RuntimeQuestionnaireRecord>) = delegate.save(records)
}
