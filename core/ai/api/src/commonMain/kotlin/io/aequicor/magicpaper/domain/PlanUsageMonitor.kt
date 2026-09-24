package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Subscription allowances, latest per provider. Engines push what arrives with their responses; [refresh] asks a
 * provider that answers between requests. Nothing is persisted: the figures age within hours.
 */
interface PlanUsageMonitor {
    val state: StateFlow<Map<ProviderType, PlanUsage>>

    fun observe(usage: PlanUsage)

    /** Asynchronous and throttled; a failure keeps the last figures and marks them stale. */
    fun refresh(provider: ProviderType)
}
