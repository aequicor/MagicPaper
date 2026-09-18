package io.aequicor.magicpaper.domain

fun MediaModelSelection.minimumProbeVideoDurationSeconds(): Int = if (modelId.startsWith("wan2.7")) 2 else 5
