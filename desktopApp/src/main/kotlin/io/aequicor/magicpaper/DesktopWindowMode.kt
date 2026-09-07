package io.aequicor.magicpaper

internal enum class DesktopWindowMode {
    MAC_SYSTEM,
    WINDOWS_JBR_CUSTOM,
    WINDOWS_SYSTEM_FALLBACK,
    LINUX_CUSTOM,
}

internal fun desktopWindowMode(
    osName: String,
    windowDecorationsSupported: Boolean,
): DesktopWindowMode {
    val normalized = osName.lowercase()
    return when {
        normalized.startsWith("mac") || normalized.contains("darwin") -> DesktopWindowMode.MAC_SYSTEM
        normalized.startsWith("windows") && windowDecorationsSupported -> DesktopWindowMode.WINDOWS_JBR_CUSTOM
        normalized.startsWith("windows") -> DesktopWindowMode.WINDOWS_SYSTEM_FALLBACK
        else -> DesktopWindowMode.LINUX_CUSTOM
    }
}
