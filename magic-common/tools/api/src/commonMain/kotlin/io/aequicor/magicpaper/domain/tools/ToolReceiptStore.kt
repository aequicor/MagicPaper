package io.aequicor.magicpaper.domain.tools

interface ToolReceiptStore {
    suspend fun get(id: String): ToolReceipt?
    suspend fun save(receipt: ToolReceipt)
    suspend fun claim(receipt: ToolReceipt): ToolReceipt?
    suspend fun forRequest(prefix: String): List<ToolReceipt>
    /** Null means the adapter cannot prove that every historical request was inspected. */
    suspend fun forOwner(projectId: String, ownerSessionId: String): List<ToolReceipt>? = null
}
