package io.aequicor.magicpaper.ui

/** Navigation data supplied by a host; common settings never names an executable capability. */
data class SettingsNavigationEntry(val icon: String, val title: String, val subtitle: String, val destination: SettingsOutput)

data class SettingsPageRegistration(
    val page: SettingsPage,
    val entry: SettingsNavigationEntry,
    val factory: SettingsComponent.Factory,
)

/** Immutable application assembly, scoped to this runtime rather than a global registry. */
class SettingsContributions(
    pages: List<SettingsPageRegistration> = emptyList(),
    navigation: List<SettingsNavigationEntry> = emptyList(),
) {
    val pages = pages.toList()
    val navigation = navigation.toList()
    init { require(this.pages.map { it.page }.distinct().size == this.pages.size) { "Duplicate settings page registration" } }
}
