package io.aequicor.magicpaper.logging

import kotlin.concurrent.thread
import kotlin.test.*

class AppLoggerConcurrencyTest {
    @Test fun concurrentOwnersKeepBoundedCompleteEntries() {
        val log = AppLogger(sink = AppLogSink {}, retention = 64)
        val workers = List(8) { owner -> thread {
            repeat(100) { generation -> log.info("drafts", "saved", mapOf("requestId" to "owner-$owner", "generation" to "$generation")) }
        } }
        workers.forEach { it.join() }
        assertEquals(64, log.history().size)
        assertTrue(log.history().all { it.fields.keys == setOf("requestId", "generation") })
    }
}
