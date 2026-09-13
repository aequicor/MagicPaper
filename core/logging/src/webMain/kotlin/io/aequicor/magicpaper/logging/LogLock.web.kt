package io.aequicor.magicpaper.logging

// A JS/Wasm module instance runs on its own browser event loop.
internal actual class LogLock actual constructor() {
    actual fun <T> locked(block: () -> T): T = block()
}
