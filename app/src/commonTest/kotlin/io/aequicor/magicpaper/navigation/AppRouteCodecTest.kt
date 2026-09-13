package io.aequicor.magicpaper.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppRouteCodecTest {
    @Test fun routesRoundTripWithEncodedIds() {
        listOf(AppRoute.Chat(), AppRoute.Chat("сессия+ one"), AppRoute.Projects(),
            AppRoute.Projects("p", "s"), AppRoute.Settings(), AppRoute.Settings(SettingsSection.MODELS),
            AppRoute.Settings(SettingsSection.ENGINES), AppRoute.Settings(SettingsSection.PROFILE, "profile"),
            AppRoute.Docs("guide"), AppRoute.Plugins("local-skills")).forEach { route ->
            assertEquals(route, AppRouteCodec.parsePath(AppRouteCodec.path(route)))
            assertEquals(route, AppRouteCodec.parseDeepLink(AppRouteCodec.deepLink(route)))
        }
    }

    @Test fun malformedOrActionLinksAreRejected() {
        listOf("magicpaper://projects/p/run", "magicpaper://chat/%2F", "magicpaper://chat/%", "magicpaper://chat/%FF",
            "magicpaper://settings?apiKey=secret", "magicpaper://chat/..", "magicpaper://chat/%00", "https://example.com/chat")
            .forEach { assertNull(AppRouteCodec.parseDeepLink(it), it) }
    }
}
