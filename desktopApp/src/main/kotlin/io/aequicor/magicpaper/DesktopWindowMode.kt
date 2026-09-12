package io.aequicor.magicpaper

internal enum class DesktopWindowMode {
    /** macOS: прозрачный нативный тайтлбар, системные декорации. */
    MAC_SYSTEM,
    /** Windows и Linux: Compose рисует весь хром окна, нативных декораций нет. */
    LINUX_CUSTOM,
}

internal fun desktopWindowMode(osName: String): DesktopWindowMode {
    val normalized = osName.lowercase()
    return if (normalized.startsWith("mac") || normalized.contains("darwin")) {
        DesktopWindowMode.MAC_SYSTEM
    } else {
        DesktopWindowMode.LINUX_CUSTOM
    }
}
