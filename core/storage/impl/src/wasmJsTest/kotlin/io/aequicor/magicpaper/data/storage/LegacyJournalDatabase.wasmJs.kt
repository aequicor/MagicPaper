package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual suspend fun seedLegacyJournalDatabase(database: String): Unit = suspendCancellableCoroutine { continuation ->
    seedLegacyDatabase(database) { failure ->
        if (continuation.isActive) {
            if (failure == null) continuation.resume(Unit)
            else continuation.resumeWithException(IllegalStateException(failure))
        }
    }
}

@JsFun("""(database, callback) => {
    const request = indexedDB.open(database, 2);
    request.onupgradeneeded = () => {
        for (const name of ["secrets", "drafts", "draft-blobs", "navigation", "view-states"])
            request.result.createObjectStore(name);
    };
    request.onerror = () => callback(request.error.name);
    request.onsuccess = () => {
        const db = request.result;
        const tx = db.transaction("drafts", "readwrite");
        tx.objectStore("drafts").put("v2-payload", "legacy");
        tx.oncomplete = () => { db.close(); callback(null); };
        tx.onabort = () => { db.close(); callback("Legacy seed failed"); };
    };
}""")
private external fun seedLegacyDatabase(database: String, callback: (String?) -> Unit)
