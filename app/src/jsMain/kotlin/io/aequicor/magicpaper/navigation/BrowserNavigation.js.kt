@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.aequicor.magicpaper.navigation

import kotlin.js.JsAny

internal actual fun navigationJsString(value: String): JsAny = value
internal actual fun navigationString(value: JsAny?): String? = value as? String
actual fun browserDocumentHidden(): Boolean = js("document.hidden") as Boolean

internal actual fun requestBrowserJournalLease(name: String, onResult: (Int) -> Unit): () -> Unit {
    val release = js("""(function() {
        var cancelled = false;
        var unlock = null;
        if (!navigator.locks) { onResult(-1); return function() {}; }
        navigator.locks.request(name, { ifAvailable: true }, function(lock) {
            if (cancelled) return;
            if (!lock) { onResult(0); return; }
            return new Promise(function(resolve) { unlock = resolve; onResult(1); });
        }).catch(function() { if (!cancelled) onResult(-1); });
        return function() { cancelled = true; if (unlock) unlock(); };
    })()""")
    return { release() }
}
