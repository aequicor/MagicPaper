package io.aequicor.magicpaper.data.coding

import java.io.IOException
import java.net.URL

internal class CodingResourceException(cause: IOException) : IOException(
    "Не удалось прочитать компоненты движка. Перезапустите приложение; если ошибка повторится, переустановите приложение.",
    cause,
)

internal fun readCodingResource(resource: URL): ByteArray = try {
    // A desktop rebuild can replace this JAR while the application is open.
    // Cached JarURLConnection entry offsets then refer to the previous archive.
    resource.openConnection().apply { useCaches = false }.getInputStream().use { it.readBytes() }
} catch (failure: IOException) {
    // The runtime/service owns logging; preserve the cause without exposing paths in the UI.
    throw CodingResourceException(failure)
}
