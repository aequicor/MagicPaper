package io.aequicor.magicpaper.data.computer

/** Only the outgoing context changes; durable tool messages and user attachments remain intact. */
internal object PiScreenshotContext {
    val source: String by lazy { checkNotNull(javaClass.getResourceAsStream("/computer/screenshot-context.js"))
        .bufferedReader().use { it.readText() }
    }
}
