package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.RuntimeQuestionnaireRecord
import io.aequicor.magicpaper.domain.RuntimeQuestionnaireStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Constructing or refusing a runtime must not create its filesystem state before preflight. */
internal class FileRuntimeQuestionnaireStore(private val directory: File) : RuntimeQuestionnaireStore {
    private val file get() = File(directory, "runtime-questionnaires.json")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(RuntimeQuestionnaireRecord.serializer())
    override fun load(): List<RuntimeQuestionnaireRecord> = synchronized(this) {
        if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyList()
    }
    override fun save(records: List<RuntimeQuestionnaireRecord>) = synchronized(this) {
        // RuntimeQuestionnaires has already redacted secret answers before this snapshot.
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File.createTempFile("questionnaires-", ".pending", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.encodeToString(serializer, records).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Unit
        } finally { temporary.delete() }
    }
}
