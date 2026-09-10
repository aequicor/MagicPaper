package io.aequicor.magicpaper.domain

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable

/** Тип вложения: как его нести модели и показывать в ленте. */
@Serializable
enum class AttachmentKind {
    /** Изображение — уходит модели инлайн (vision) и показывается превью. */
    IMAGE,

    /** Текстовый файл — содержимое вкладывается в сообщение текстом. */
    TEXT,

    /** Прочий бинарный формат: в чат не передаётся, но доступен кодинг-агенту как файл. */
    FILE,
}

/**
 * Вложение сообщения (файл, прикреплённый пользователем).
 * Содержимое хранится в base64 и сериализуется вместе с историей
 * (свитки, журнал кодинг-сессий, экспорт профиля).
 */
@Serializable
data class Attachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dataBase64: String,
    val kind: AttachmentKind,
) {
    /** Декодированные байты (лениво, кэшируются). */
    @OptIn(ExperimentalEncodingApi::class)
    val bytes: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) { Base64.decode(dataBase64) }

    /** Видно ли вложение модели в чате (изображение или текст). */
    val visibleToChat: Boolean get() = kind == AttachmentKind.IMAGE || kind == AttachmentKind.TEXT

    companion object {
        /** Сборка вложения из байтов: тип определяется по mime/расширению. */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromBytes(name: String, mimeType: String?, bytes: ByteArray): Attachment {
            val mime = mimeType?.takeIf { it.isNotBlank() } ?: mimeFromName(name)
            return Attachment(
                id = io.aequicor.magicpaper.util.Id.new(),
                name = name,
                mimeType = mime,
                sizeBytes = bytes.size.toLong(),
                dataBase64 = Base64.encode(bytes),
                kind = kindOf(mime, name),
            )
        }

        fun kindOf(mimeType: String, name: String): AttachmentKind = when {
            mimeType.startsWith("image/") || IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } ->
                AttachmentKind.IMAGE
            mimeType.startsWith("text/") || mimeType in TEXT_MIME ||
                TEXT_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } -> AttachmentKind.TEXT
            else -> AttachmentKind.FILE
        }
    }
}

/**
 * Метаданные вложения в журнале кодинг-сессии: сам файл лежит на диске
 * (изолированная папка рантайма), в журнал попадает только описание.
 */
@Serializable
data class AttachmentMeta(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: AttachmentKind,
    /** Абсолютный путь, куда рантайм положил файл (если известно). */
    val path: String = "",
)

/** Origin of an image that the coding transcript is allowed to present. */
@Serializable
enum class CodingImageSource { USER_ATTACHMENT, TOOL_RESULT }

/**
 * Only application-owned bytes may be rendered from coding history. A locator
 * intentionally cannot represent a URL or arbitrary local path: those are
 * transport text, not an image capability.
 */
@Serializable
sealed interface CodingImageLocator {
    @Serializable
    data class InlineBase64(val dataBase64: String) : CodingImageLocator

    /** Relative name in the managed image store; platform loaders validate it before reading. */
    @Serializable
    data class ManagedBlob(val blobId: String) : CodingImageLocator
}

/** Durable, typed identity of an image in a coding invocation. */
@Serializable
data class CodingImageReference(
    val imageId: String,
    val source: CodingImageSource,
    val sessionId: String,
    val invocationId: String,
    val timelineId: String,
    val ownerMessageId: String,
    /** Null only for USER_ATTACHMENT belonging to the invocation input. */
    val callId: String? = null,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val locator: CodingImageLocator,
)

/** A persisted invocation supplies the non-heuristic ownership envelope. */
@Serializable
data class CodingImageInvocation(
    val sessionId: String,
    val invocationId: String,
    val inputMessageId: String,
    val responseMessageId: String,
    val responseTimelineId: String,
)

/** Structured result supplied by an adapter's confirmed terminal event. */
data class CodingImageArtifact(
    val id: String = "",
    val name: String = "",
    val mimeType: String,
    val sizeBytes: Long,
    val dataBase64: String,
)

/** Input images may only be displayed on their exact USER message. */
fun CodingImageReference.isInputFor(message: CodingMessage): Boolean =
    source == CodingImageSource.USER_ATTACHMENT && callId == null &&
        message.role == CodingRole.USER && ownerMessageId == message.id

/** Tool images may only be displayed on the exact AGENT step which produced them. */
fun CodingImageReference.isResultFor(message: CodingMessage, step: CodingStep): Boolean =
    source == CodingImageSource.TOOL_RESULT && message.role == CodingRole.AGENT &&
        ownerMessageId == message.id && timelineId == message.timelineId && callId != null && callId == step.callId

fun Attachment.asCodingInputImage(invocation: CodingImageInvocation): CodingImageReference? =
    takeIf { it.kind == AttachmentKind.IMAGE && it.dataBase64.isNotBlank() }?.let {
        CodingImageReference(
            imageId = it.id,
            source = CodingImageSource.USER_ATTACHMENT,
            sessionId = invocation.sessionId,
            invocationId = invocation.invocationId,
            timelineId = invocation.responseTimelineId,
            ownerMessageId = invocation.inputMessageId,
            name = it.name,
            mimeType = it.mimeType,
            sizeBytes = it.sizeBytes,
            locator = CodingImageLocator.InlineBase64(it.dataBase64),
        )
    }

/** Содержимое файла, выбранного пользователем (до упаковки в [Attachment]). */
data class PickedFile(
    val name: String,
    val mimeType: String?,
    val bytes: ByteArray,
)

/**
 * Платформенный выбор файлов для вложений (десктоп — нативный диалог,
 * браузер — input, Android пока без реализации).
 */
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

/** Максимум вложений на одно сообщение. */
const val MAX_ATTACHMENTS_PER_MESSAGE = 8

/** Метаданные вложения для журнала кодинг-сессии (без содержимого). */
fun Attachment.asMeta(path: String = ""): AttachmentMeta =
    AttachmentMeta(name, mimeType, sizeBytes, kind, path)

/** Вложения, которые видит модель в чате: изображения и текст. */
fun List<Attachment>.chatVisible(): List<Attachment> = filter { it.visibleToChat }

/** Текстовое содержимое вложения (для текстовых файлов). */
fun Attachment.decodeText(): String = bytes.decodeToString()

/** MIME текстовых форматов, которые уходят модели содержимым. */
val TEXT_MIME = setOf(
    "application/json",
    "application/xml",
    "application/x-yaml",
    "application/yaml",
    "application/javascript",
    "application/typescript",
    "application/x-sh",
    "application/sql",
    "application/csv",
)

val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg")

val TEXT_EXTENSIONS = listOf(
    ".txt", ".md", ".markdown", ".rst",
    ".kt", ".kts", ".java", ".py", ".js", ".mjs", ".ts", ".tsx", ".jsx",
    ".c", ".h", ".cpp", ".hpp", ".cs", ".go", ".rs", ".rb", ".php", ".swift", ".scala",
    ".html", ".htm", ".css", ".scss", ".json", ".xml", ".yaml", ".yml", ".toml",
    ".ini", ".conf", ".properties", ".sh", ".bat", ".ps1", ".sql", ".csv", ".log",
    ".gradle", ".dockerfile", ".env", ".gitignore",
)

/** Определение MIME по расширению — фолбэк, когда платформа его не сообщила. */
fun mimeFromName(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".bmp") -> "image/bmp"
        lower.endsWith(".svg") -> "image/svg+xml"
        lower.endsWith(".pdf") -> "application/pdf"
        lower.endsWith(".zip") -> "application/zip"
        lower.endsWith(".json") -> "application/json"
        lower.endsWith(".xml") -> "application/xml"
        lower.endsWith(".csv") -> "application/csv"
        lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log") -> "text/plain"
        TEXT_EXTENSIONS.any { lower.endsWith(it) } -> "text/plain"
        else -> "application/octet-stream"
    }
}

/** Человекочитаемый размер: 1.2 МБ, 340 КБ. */
fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${trimZero(bytes / 1024.0)} КБ"
    else -> "${trimZero(bytes / 1024.0 / 1024.0)} МБ"
}

private fun trimZero(value: Double): String {
    val rounded = (value * 10).toLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
