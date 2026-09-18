package io.aequicor.magicpaper

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

    @Test fun differentBuildLocationsOwnIndependentActivationNamespaces() {
        val root = Files.createTempDirectory("magicpaper-activation-namespaces")
        val installed = activationDirectoryFor(root, "installed")
        val development = activationDirectoryFor(root, "development")
        assertNotEquals(installed, development, "A development launch must not join the installed namespace")
        val firstDelivered = mutableListOf<List<String>>()
        val latch = CountDownLatch(1)
        val installedOwner = assertIs<DesktopActivationBroker.Result.Primary>(
            DesktopActivationBroker.acquire(installed, emptyList(), {
                synchronized(firstDelivered) { firstDelivered += it }; latch.countDown()
            }))
        // The installed runtime is alive: a launch from another location still becomes primary.
        val developmentOwner = assertIs<DesktopActivationBroker.Result.Primary>(
            DesktopActivationBroker.acquire(development, emptyList(), { error("Another build must not receive this activation") }))
        try {
            assertIs<DesktopActivationBroker.Result.Forwarded>(DesktopActivationBroker.acquire(installed,
                listOf("magicpaper://settings/models"), { error("Same-build second launch must forward") }))
            assertTrue(latch.await(3, TimeUnit.SECONDS), "Only the installed namespace received the link")
            assertEquals(listOf(listOf("magicpaper://settings/models")), synchronized(firstDelivered) { firstDelivered.toList() })
        } finally {
            developmentOwner.broker.close()
            installedOwner.broker.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun unknownApplicationLocationKeepsTheSharedNamespace() {
        val root = Files.createTempDirectory("magicpaper-activation-legacy")
        assertEquals(root, DesktopActivationBroker.activationDirectory(root, null))
        val owner = assertIs<DesktopActivationBroker.Result.Primary>(DesktopActivationBroker.acquire(root, emptyList(), {}))
        try {
            assertIs<DesktopActivationBroker.Result.Forwarded>(
                DesktopActivationBroker.acquire(DesktopActivationBroker.activationDirectory(root, null), emptyList(), { error("Secondary must not initialize") }))
        } finally { owner.broker.close(); root.toFile().deleteRecursively() }
    }

    /** The host must know which build it runs from, otherwise every launch shares one namespace. */
    @Test fun desktopHostDerivesItsNamespaceFromTheRunningBuildLocation() {
        val location = assertNotNull(currentApplicationLocation())
        assertTrue(Files.isDirectory(location), "Expected the directory holding this build's code, got $location")
        val root = Path.of(System.getProperty("user.home"), ".MagicPaper", "activation")
        val directory = DesktopActivationBroker.activationDirectory(root, location)
        assertNotEquals(root, directory, "A launch from this build owns a namespace of its own")
        assertEquals(root, directory.parent, "Namespaces stay inside the user's activation root")
    }

    @Test fun staleEndpointIsReplacedOnlyAfterOwningLock() {
        val directory = Files.createTempDirectory("magicpaper-stale-activation")
        Files.writeString(directory.resolve("endpoint.properties"), "port=1\npid=1\n")
        val owner = assertIs<DesktopActivationBroker.Result.Primary>(DesktopActivationBroker.acquire(directory, emptyList(), {}))
        try { assertTrue(Files.readString(directory.resolve("endpoint.properties")).contains("version=1")) }
        finally { owner.broker.close(); directory.toFile().deleteRecursively() }
    }

    /**
     * Namespace of an application whose code lives in its own `app` directory, plus the
     * guarantee that another spelling of the same directory selects the same owner.
     */
    private fun activationDirectoryFor(root: Path, name: String): Path {
        val applicationDirectory = Files.createTempDirectory(root, "image-").resolve(name).resolve("app")
        Files.createDirectories(applicationDirectory)
        val directory = DesktopActivationBroker.activationDirectory(root, applicationDirectory)
        assertEquals(directory, DesktopActivationBroker.activationDirectory(root, applicationDirectory.resolve("classes").parent),
            "One installation keeps one namespace")
        return directory
    }
}
