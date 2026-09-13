package io.aequicor.magicpaper

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopActivationBrokerTest {
    @Test fun secondLaunchForwardsWithoutBecomingPrimaryAndLockCanBeReacquired() {
        val directory = Files.createTempDirectory("magicpaper-activation-test")
        val delivered = mutableListOf<List<String>>()
        val latch = CountDownLatch(1)
        val first = assertIs<DesktopActivationBroker.Result.Primary>(DesktopActivationBroker.acquire(directory, emptyList(), {
            synchronized(delivered) { delivered += it }; latch.countDown()
        }))
        try {
            assertIs<DesktopActivationBroker.Result.Forwarded>(DesktopActivationBroker.acquire(directory,
                listOf("magicpaper://projects/p/sessions/s"), { error("Secondary must not initialize") }))
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(listOf("magicpaper://projects/p/sessions/s")), synchronized(delivered) { delivered.toList() })
        } finally { first.broker.close() }
        val next = assertIs<DesktopActivationBroker.Result.Primary>(DesktopActivationBroker.acquire(directory, emptyList(), {}))
        next.broker.close()
        directory.toFile().deleteRecursively()
    }

    @Test fun staleEndpointIsReplacedOnlyAfterOwningLock() {
        val directory = Files.createTempDirectory("magicpaper-stale-activation")
        Files.writeString(directory.resolve("endpoint.properties"), "port=1\npid=1\n")
        val owner = assertIs<DesktopActivationBroker.Result.Primary>(DesktopActivationBroker.acquire(directory, emptyList(), {}))
        try { assertTrue(Files.readString(directory.resolve("endpoint.properties")).contains("version=1")) }
        finally { owner.broker.close(); directory.toFile().deleteRecursively() }
    }
}
