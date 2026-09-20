package io.aequicor.magicpaper.data.coding

import java.io.File

/** Only installed engine code is copied. No app history, credentials, skills or settings enter fixtures. */
internal fun isolatedPiRuntime(root: File): PiCodingRuntime {
    val source = File(System.getProperty("user.home"), ".MagicPaper/coding/prefix")
    check(File(source, "node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js").isFile) {
        "Install the Pi engine before running local native fixtures"
    }
    val target = File(root, "pi-runtime")
    check(source.copyRecursively(File(target, "prefix"))) { "Could not copy installed engine into the fixture" }
    return PiCodingRuntime(target)
}
