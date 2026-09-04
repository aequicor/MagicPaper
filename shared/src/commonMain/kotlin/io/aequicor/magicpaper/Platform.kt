package io.aequicor.magicpaper

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform