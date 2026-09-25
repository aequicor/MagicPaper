package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Subscription allowances arrive with engine responses and can also be queried between requests. A refresh of each
 * provider repeats at most once per [minInterval].
 */
class DefaultPlanUsageMonitor(
    private val scope: CoroutineScope,
    private val openAiSubscription: OpenAiSubscriptionService? = null,
    private val claudeSubscription: ClaudeSubscriptionService? = null,
    private val now: () -> Long = Id::now,
    private val minInterval: Long = 30_000,
) : PlanUsageMonitor {
    private val mutableState = MutableStateFlow<Map<ProviderType, PlanUsage>>(emptyMap())
    override val state = mutableState.asStateFlow()
    private class RefreshSlot(val mutex: Mutex = Mutex(), var lastAt: Long = Long.MIN_VALUE)
    private val refreshSlots = mapOf(ProviderType.OPENAI_SUBSCRIPTION to RefreshSlot(),
        ProviderType.ANTHROPIC_SUBSCRIPTION to RefreshSlot())

    override fun observe(usage: PlanUsage) {
        mutableState.update { plans -> plans + (usage.provider to (plans[usage.provider]?.merge(usage) ?: usage)) }
    }

    override fun refresh(provider: ProviderType) {
        if (provider == ProviderType.OPENAI_SUBSCRIPTION && openAiSubscription == null) return
        if (provider == ProviderType.ANTHROPIC_SUBSCRIPTION && claudeSubscription == null) return
        val slot = refreshSlots[provider] ?: return
        scope.launch {
            if (!slot.mutex.tryLock()) return@launch
            try {
                if (slot.lastAt != Long.MIN_VALUE && now() - slot.lastAt < minInterval) return@launch
                slot.lastAt = now()
                val result = when (provider) {
                    ProviderType.OPENAI_SUBSCRIPTION -> {
                        val account = checkNotNull(openAiSubscription).account()
                        val usage = account.planUsage(now())
                        when {
                            usage != null -> mutableState.update { it + (provider to usage) }
                            account.signedIn -> markStale(provider)
                            else -> mutableState.update { it - provider }
                        }
                        if (usage != null) "observed" else if (account.signedIn) "unavailable" else "signed_out"
                    }
                    ProviderType.ANTHROPIC_SUBSCRIPTION -> {
                        val usage = checkNotNull(claudeSubscription).planUsage()
                        if (usage != null && !usage.stale) observe(usage) else markStale(provider)
                        if (usage != null && !usage.stale) "observed" else "unavailable"
                    }
                    else -> return@launch
                }
                AppLog.debug("planUsage", "refresh_completed", mapOf("provider" to provider.name,
                    "result" to result))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.error("planUsage", "refresh_failed", failure, mapOf("provider" to provider.name, "result" to "stale"))
                markStale(provider)
            } finally {
                slot.mutex.unlock()
            }
        }
    }

    /** The last figures stay visible, marked as unconfirmed; with none, the provider shows as unavailable. */
    private fun markStale(provider: ProviderType) = mutableState.update { plans ->
        plans + (provider to (plans[provider]?.copy(stale = true) ?: PlanUsage(provider, observedAt = now(), stale = true)))
    }
}
