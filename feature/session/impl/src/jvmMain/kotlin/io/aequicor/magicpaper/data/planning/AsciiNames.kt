package io.aequicor.magicpaper.data.planning

/**
 * Имена Git-объектов приложения остаются осознанными и ASCII: ветка задачи получает слаг её
 * запроса, а коммит — транслитерированную строку вместо хеша идентификатора. Юникод и служебные
 * символы не попадают ни в ref, ни в subject: Git-клиенты и логи показывают читаемый текст.
 */
private val CYRILLIC_ASCII = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
    'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
    'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
    'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "",
    'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    'і' to "i", 'ї' to "i", 'є' to "ye", 'ґ' to "g",
)

/** Кириллица транслитерируется, прочие не-ASCII символы становятся разделителями. */
internal fun transliterateAscii(text: String): String = buildString(text.length) {
    for (ch in text) {
        val mapped = CYRILLIC_ASCII[ch.lowercaseChar()]
        when {
            mapped != null -> append(if (ch.isUpperCase()) mapped.replaceFirstChar { it.uppercaseChar() } else mapped)
            ch.code < 128 -> append(ch)
            else -> append(' ')
        }
    }
}

/** Ref-safe slug: только `[a-z0-9-]`, без удвоенных и краевых дефисов, с обрезкой по границе слова. */
internal fun asciiSlug(text: String, limit: Int): String {
    val slug = transliterateAscii(text).lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
    if (slug.length <= limit) return slug
    val cut = slug.take(limit).removeSuffix("-")
    return cut.substringBeforeLast('-', cut).trim('-')
}

/** Однострочный subject коммита: переносы и повторы пробелов схлопываются, юникод транслитерируется. */
internal fun asciiSubject(text: String, limit: Int): String =
    transliterateAscii(text).replace(Regex("\\s+"), " ").trim().take(limit).trim()
