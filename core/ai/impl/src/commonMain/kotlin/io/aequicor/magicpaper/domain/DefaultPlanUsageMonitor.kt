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
 * Only the ChatGPT subscription answers between requests (Codex app-server `account/rateLimits/read`); Claude's
 * allowance arrives solely with its engine's responses. A refresh repeats at most once per [minInterval].
 */
class DefaultPlanUsageMonitor(
    private val scope: CoroutineScope,
    private val openAiSubscription: OpenAiSubscriptionService? = null,
    private val now: () -> Long = Id::now,
    private val minInterval: Long = 30_000,
) : PlanUsageMonitor {
    private val mutableState = MutableStateFlow<Map<ProviderType, PlanUsage>>(emptyMap())
    override val state = mutableState.asStateFlow()
    private val refreshing = Mutex()
    private var lastRefreshAt = Long.MIN_VALUE

    override fun observe(usage: PlanUsage) {
        mutableState.update { plans -> plans + (usage.provider to (plans[usage.provider]?.merge(usage) ?: usage)) }
    }

    override fun refresh(provider: ProviderType) {
        if (provider != ProviderType.OPENAI_SUBSCRIPTION) return
        val subscription = openAiSubscription ?: return
        scope.launch {
            if (!refreshing.tryLock()) return@launch
            try {
                if (lastRefreshAt != Long.MIN_VALUE && now() - lastRefreshAt < minInterval) return@launch
                lastRefreshAt = now()
                val account = subscription.account()
                val usage = account.planUsage(now())
                when {
                    usage != null -> mutableState.update { it + (provider to usage) }
                    account.signedIn -> markStale(provider)
                    else -> mutableState.update { it - provider }
                }
                AppLog.debug("planUsage", "refresh_completed", mapOf("provider" to provider.name,
                    "result" to if (usage != null) "observed" else if (account.signedIn) "unavailable" else "signed_out"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.error("planUsage", "refresh_failed", failure, mapOf("provider" to provider.name, "result" to "stale"))
                markStale(provider)
            } finally {
                refreshing.unlock()
            }
        }
    }

    /** The last figures stay visible, marked as unconfirmed; with none, the provider shows as unavailable. */
    private fun markStale(provider: ProviderType) = mutableState.update { plans ->
        plans + (provider to (plans[provider]?.copy(stale = true) ?: PlanUsage(provider, observedAt = now(), stale = true)))
    }
}
