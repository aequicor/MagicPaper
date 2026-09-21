package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual suspend fun seedLegacyJournalDatabase(database: String, version: Int): Unit = suspendCancellableCoroutine { continuation ->
    seedLegacyDatabase(database, version) { failure ->
        if (continuation.isActive) {
            if (failure == null) continuation.resume(Unit)
            else continuation.resumeWithException(IllegalStateException(failure))
        }
    }
}

@JsFun("""(database, version, callback) => {
    const request = indexedDB.open(database, version);
    request.onupgradeneeded = () => {
        const names = ["secrets", "drafts", "draft-blobs", "navigation", "view-states"];
        if (version >= 3) names.push("events");
        for (const name of names) request.result.createObjectStore(name);
    };
    request.onerror = () => callback(request.error.name);
    request.onsuccess = () => {
        const db = request.result;
        const stores = version >= 3 ? ["drafts", "navigation", "events"] : ["drafts"];
        const tx = db.transaction(stores, "readwrite");
        tx.objectStore("drafts").put("v" + version + "-payload", "legacy");
        if (version >= 3) {
            tx.objectStore("navigation").put("5", "\u0000magicpaper-reset-epoch");
            tx.objectStore("events").put("1", "seq");
            tx.objectStore("events").put(JSON.stringify({storageFormat: "magicpaper-journal", version: 1,
                seq: 1, at: 1, stream: "legacy-owner", operation: "saved", detail: "private-reference"}), "e000000000000000001");
        }
        tx.oncomplete = () => { db.close(); callback(null); };
        tx.onabort = () => { db.close(); callback("Legacy seed failed"); };
    };
}""")
private external fun seedLegacyDatabase(database: String, version: Int, callback: (String?) -> Unit)
