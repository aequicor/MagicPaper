package io.aequicor.magicpaper.data.claude

/**
 * The image formats Claude takes, recognised by their bytes. Claude Code's Read tool shows a file as an image only by
 * its extension (png, jpg, jpeg, gif, webp) and the API accepts only these media types, so neither a missing or wrong
 * extension nor a declared MIME type that differs from the content may decide it.
 */
internal enum class ClaudeImageFormat(val extension: String, val mediaType: String) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    GIF("gif", "image/gif"),
    WEBP("webp", "image/webp"),
    ;

    companion object {
        fun of(bytes: ByteArray): ClaudeImageFormat? = when {
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> PNG
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> JPEG
            bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> GIF
            bytes.size >= 12 && bytes.startsWith(0x52, 0x49, 0x46, 0x46) &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray()) -> WEBP
            else -> null
        }

        /** [name] with the extension of its real format, so the CLI reads the file as the image it is. */
        fun fileName(name: String, format: ClaudeImageFormat): String {
            val stem = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "").lowercase()
            val matches = extension == format.extension || (format == JPEG && extension == "jpeg")
            return if (matches) name else "${stem.ifBlank { "image" }}.${format.extension}"
        }

        private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
    }
}
