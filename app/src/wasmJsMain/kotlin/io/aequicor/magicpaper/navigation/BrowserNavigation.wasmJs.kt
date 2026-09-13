@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.aequicor.magicpaper.navigation

import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.toJsString

internal actual fun navigationJsString(value: String): JsAny = value.toJsString()
internal actual fun navigationString(value: JsAny?): String? = (value as? JsString)?.toString()

@JsFun("() => document.hidden")
private external fun documentHidden(): Boolean

actual fun browserDocumentHidden(): Boolean = documentHidden()

@JsFun("""(name, onResult) => {
    let cancelled = false;
    let unlock = null;
    if (!navigator.locks) { onResult(-1); return () => {}; }
    navigator.locks.request(name, { ifAvailable: true }, lock => {
        if (cancelled) return;
        if (!lock) { onResult(0); return; }
        return new Promise(resolve => { unlock = resolve; onResult(1); });
    }).catch(() => { if (!cancelled) onResult(-1); });
    return () => { cancelled = true; if (unlock) unlock(); };
}""")
private external fun requestJournalLease(name: String, onResult: (Int) -> Unit): JsAny

@JsFun("(release) => release()")
private external fun releaseJournalLease(release: JsAny)

internal actual fun requestBrowserJournalLease(name: String, onResult: (Int) -> Unit): () -> Unit {
    val release = requestJournalLease(name, onResult)
    return { releaseJournalLease(release) }
}
