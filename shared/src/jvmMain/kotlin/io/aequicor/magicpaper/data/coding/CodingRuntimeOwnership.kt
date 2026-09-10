package io.aequicor.magicpaper.data.coding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** An in-process fence complements the persisted generation checked by the application host. */
internal class CodingRuntimeOwnership {
    data class Lease(val sessionId: String, val generation: Long, val job: Job)
    private val leases = mutableMapOf<String, Lease>()
    private val generations = mutableMapOf<String, Long>()

    @Synchronized fun begin(sessionId: String, generation: Long, job: Job): Lease {
        check(sessionId !in leases) { "Сессия уже выполняется или останавливается" }
        check(generation >= (generations[sessionId] ?: 0)) { "Поколение сессии устарело" }
        check(job.isActive) { "Запуск отменён" }
        return Lease(sessionId, generation, job).also { leases[sessionId] = it; generations[sessionId] = generation }
    }
    @Synchronized fun checkCurrent(lease: Lease) {
        if (leases[lease.sessionId] !== lease || !lease.job.isActive)
            throw CancellationException("Полномочия запуска отозваны")
    }
    @Synchronized fun finish(lease: Lease) { if (leases[lease.sessionId] === lease) leases.remove(lease.sessionId) }
    @Synchronized fun cancel(sessionId: String) { leases[sessionId]?.job?.cancel(CancellationException("Сессия остановлена")) }
    @Synchronized fun cancelAll() { leases.values.toList().forEach { it.job.cancel(CancellationException("Приложение останавливается")) } }
}
