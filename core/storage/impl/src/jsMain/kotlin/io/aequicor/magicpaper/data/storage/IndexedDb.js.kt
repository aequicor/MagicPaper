package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual suspend fun indexedDbOperation(database: String, store: String, key: String, operation: String, value: String?): String? =
    suspendCancellableCoroutine { continuation ->
        performIndexedDb(database, store, key, operation, value) { result, error ->
            if (continuation.isActive) {
                if (error == null) continuation.resume(result)
                else continuation.resumeWithException(StorageException(operation, when (error) {
                    "QuotaExceededError" -> StorageException.Kind.QUOTA
                    "Corrupt" -> StorageException.Kind.CORRUPT
                    "Unavailable", "SecurityError", "InvalidStateError" -> StorageException.Kind.UNAVAILABLE
                    else -> if (operation == "read") StorageException.Kind.READ else StorageException.Kind.WRITE
                }))
            }
        }
    }

private fun performIndexedDb(database: String, store: String, key: String, operation: String, value: String?, callback: (String?, String?) -> Unit) {
    // Pass Kotlin values outside the JS literal: its own parameter scope must not shadow
    // Kotlin parameters that the IR backend may rename while lowering coroutine callers.
    val bridge = js("""(database, store, key, operation, value, callback) => {
    let finished = false;
    const finish = (result, failure) => {
        if (!finished) { finished = true; callback(result, failure); }
    };
    try {
        if (operation === "lock") {
            if (!globalThis.navigator || !navigator.locks) { finish(null, "Unavailable"); return; }
            navigator.locks.request("magicpaper-drafts:" + database, { mode: "exclusive" }, () => new Promise(resolve => {
                if (!globalThis.__magicPaperDraftLeases) globalThis.__magicPaperDraftLeases = new Map();
                globalThis.__magicPaperDraftLeases.set(key, resolve);
                finish(null, null);
            })).catch(error => finish(null, error.name || "Unavailable"));
            return;
        }
        if (operation === "unlock") {
            const leases = globalThis.__magicPaperDraftLeases;
            const release = leases && leases.get(key);
            if (release) { leases.delete(key); release(); }
            finish(null, null);
            return;
        }
        if (!globalThis.indexedDB) { finish(null, "Unavailable"); return; }
        const open = globalThis.indexedDB.open(database, 4);
        open.onblocked = () => finish(null, "Unavailable");
        open.onerror = () => finish(null, (open.error && open.error.name) || "Unavailable");
        open.onupgradeneeded = () => {
            for (const name of ["secrets", "drafts", "draft-blobs", "navigation", "view-states", "events", "control"]) {
                if (!open.result.objectStoreNames.contains(name)) open.result.createObjectStore(name);
            }
        };
        open.onsuccess = () => {
            const db = open.result;
            if (finished) { db.close(); return; }
            db.onversionchange = () => db.close();
            try {
                const tx = db.transaction(store, operation === "read" || operation === "values" ? "readonly" : "readwrite");
                const records = tx.objectStore(store);
                let result = null;
                const request = operation === "values" ? records.getAll() : operation === "read" ? records.get(key) : operation === "delete" ? records.delete(key) : operation === "clear" ? records.clear() :
                    records.put(store === "draft-blobs" ? Uint8Array.from(atob(value), c => c.charCodeAt(0)) : value, key);
                request.onsuccess = () => {
                    if (operation === "values") {
                        if (store === "draft-blobs") {
                            result = JSON.stringify(request.result.map(value => {
                                const bytes = new Uint8Array(value);
                                let binary = "";
                                for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                                return btoa(binary);
                            }));
                        } else if (request.result.every(value => typeof value === "string")) result = JSON.stringify(request.result);
                        else { tx.abort(); finish(null, "Corrupt"); }
                    }
                    if (operation === "read" && request.result !== undefined) {
                        if (store === "draft-blobs") {
                            const bytes = new Uint8Array(request.result);
                            let binary = "";
                            for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                            result = btoa(binary);
                        } else if (typeof request.result === "string") result = request.result;
                        else { tx.abort(); finish(null, "Corrupt"); }
                    }
                };
                tx.oncomplete = () => { db.close(); finish(result, null); };
                tx.onabort = () => { db.close(); finish(null, (tx.error && tx.error.name) || (request.error && request.error.name) || "AbortError"); };
                tx.onerror = () => { db.close(); finish(null, (tx.error && tx.error.name) || (request.error && request.error.name) || "UnknownError"); };
            } catch (error) { db.close(); finish(null, error.name || "UnknownError"); }
        };
    } catch (error) { finish(null, error.name || "Unavailable"); }
}""")
    bridge(database, store, key, operation, value, callback)
}
