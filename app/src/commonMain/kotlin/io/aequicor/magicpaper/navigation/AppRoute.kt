package io.aequicor.magicpaper.navigation

import kotlinx.serialization.Serializable

/** Public destinations contain identity only. Drafts and runtime capabilities never enter a URL. */
@Serializable
sealed interface AppRoute {
    @Serializable data class Chat(val sessionId: String? = null) : AppRoute
    @Serializable data class Projects(val projectId: String? = null, val sessionId: String? = null) : AppRoute {
        init { require(sessionId == null || projectId != null) }
    }
    @Serializable data class Settings(
        val section: SettingsSection = SettingsSection.OVERVIEW,
        val profileId: String? = null,
    ) : AppRoute {
        init { require((section == SettingsSection.PROFILE) == (profileId != null)) }
    }
    @Serializable data class Docs(val articleId: String? = null) : AppRoute
    @Serializable data class Plugins(val pluginId: String? = null) : AppRoute
}

@Serializable
enum class SettingsSection { OVERVIEW, MODELS, ENGINES, PROFILE }

/** Canonical, platform-independent route codec. Decode each segment exactly once. */
object AppRouteCodec {
    const val SCHEME = "magicpaper"
    private const val MAX_LINK_LENGTH = 8192

    fun path(route: AppRoute): String = when (route) {
        is AppRoute.Chat -> "/chat" + route.sessionId.suffix()
        is AppRoute.Projects -> "/projects" + route.projectId.suffix() +
            (route.sessionId?.let { "/sessions/${encode(it)}" } ?: "")
        is AppRoute.Settings -> when (route.section) {
            SettingsSection.OVERVIEW -> "/settings"
            SettingsSection.MODELS -> "/settings/models"
            SettingsSection.ENGINES -> "/settings/engines"
            SettingsSection.PROFILE -> "/settings/profiles/${encode(requireNotNull(route.profileId))}"
        }
        is AppRoute.Docs -> "/docs" + route.articleId.suffix()
        is AppRoute.Plugins -> "/plugins" + route.pluginId.suffix()
    }

    fun deepLink(route: AppRoute): String = "$SCHEME://${path(route).removePrefix("/")}"

    fun parseDeepLink(link: String): AppRoute? {
        if (link.length > MAX_LINK_LENGTH || !link.startsWith("$SCHEME://", ignoreCase = true)) return null
        return parsePath("/" + link.substringAfter("://"))
    }

    fun parsePath(path: String): AppRoute? = runCatching {
        require(path.length <= MAX_LINK_LENGTH && path.startsWith("/") && !path.startsWith("//"))
        require('?' !in path && '#' !in path && '\\' !in path)
        val segments = path.removePrefix("/").removeSuffix("/").split('/').map(::decode)
        require(segments.all { it.isNotBlank() && it != "." && it != ".." && it.none { c -> c.isISOControl() || c == '/' || c == '\\' } })
        when (segments.first()) {
            "chat" -> when (segments.size) { 1 -> AppRoute.Chat(); 2 -> AppRoute.Chat(segments[1]); else -> null }
            "projects" -> when {
                segments.size == 1 -> AppRoute.Projects()
                segments.size == 2 -> AppRoute.Projects(segments[1])
                segments.size == 4 && segments[2] == "sessions" -> AppRoute.Projects(segments[1], segments[3])
                else -> null
            }
            "settings" -> when {
                segments.size == 1 -> AppRoute.Settings()
                segments.size == 2 && segments[1] == "models" -> AppRoute.Settings(SettingsSection.MODELS)
                segments.size == 2 && segments[1] == "engines" -> AppRoute.Settings(SettingsSection.ENGINES)
                segments.size == 3 && segments[1] == "profiles" -> AppRoute.Settings(SettingsSection.PROFILE, segments[2])
                else -> null
            }
            "docs" -> when (segments.size) { 1 -> AppRoute.Docs(); 2 -> AppRoute.Docs(segments[1]); else -> null }
            "plugins" -> when (segments.size) { 1 -> AppRoute.Plugins(); 2 -> AppRoute.Plugins(segments[1]); else -> null }
            else -> null
        }
    }.getOrNull()

    private fun String?.suffix(): String = this?.let { "/${encode(it)}" } ?: ""

    private fun encode(value: String): String = buildString {
        require(value.isNotBlank() && value != "." && value != "..")
        require(value.none { it.isISOControl() || it == '/' || it == '\\' })
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt() and 255
            if (c in 65..90 || c in 97..122 || c in 48..57 || c.toChar() in "-._~") append(c.toChar())
            else { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
        }
    }

    private fun decode(value: String): String {
        val result = ArrayList<Byte>()
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                require(index + 2 < value.length)
                result += ((value[index + 1].digitToInt(16) shl 4) + value[index + 2].digitToInt(16)).toByte()
                index += 3
            } else {
                val end = value.indexOf('%', index).takeIf { it >= 0 } ?: value.length
                result += value.substring(index, end).encodeToByteArray().toList()
                index = end
            }
        }
        return result.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }
}

/** Diagnostics contain only the screen category, never a raw path or external URI. */
internal fun AppRoute.logKind(): String = when (this) {
    is AppRoute.Chat -> "chat"
    is AppRoute.Projects -> "projects"
    is AppRoute.Settings -> "settings"
    is AppRoute.Docs -> "docs"
    is AppRoute.Plugins -> "plugins"
}
