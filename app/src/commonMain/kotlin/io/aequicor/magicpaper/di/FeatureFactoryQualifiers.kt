package io.aequicor.magicpaper.di

import org.koin.core.qualifier.named

/** Wasm type names omit their enclosing interface: every nested contract is called Factory. */
internal object FeatureFactoryQualifiers {
    val chat = named("feature.chat.component.factory")
    val coding = named("feature.coding.component.factory")
    val settings = named("feature.settings.component.factory")
    val docs = named("feature.docs.component.factory")
    val plugins = named("feature.plugins.component.factory")
    val skills = named("feature.skills.component.factory")
}
