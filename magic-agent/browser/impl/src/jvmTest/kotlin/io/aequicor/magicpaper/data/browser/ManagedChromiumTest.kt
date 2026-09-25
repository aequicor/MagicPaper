package io.aequicor.magicpaper.data.browser

import com.microsoft.playwright.PlaywrightException
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ManagedChromiumTest {
    @Test fun nativeInstallerCanRunFromTheApplicationClasspath() {
        assumeTrue(java.lang.Boolean.getBoolean("magicpaper.browser.native"))
        installManagedChromium()
    }

    @Test fun missingManagedBrowserIsInstalledAndLaunchIsRetriedOnce() {
        var launches = 0
        var installs = 0
        val result = launchManagedChromium({
            if (++launches == 1) throw PlaywrightException("Executable doesn't exist at /private/browser")
            "opened"
        }, { installs++ })
        assertEquals("opened", result)
        assertEquals(2, launches)
        assertEquals(1, installs)
    }

    @Test fun unrelatedLaunchFailureDoesNotInstallOrRetry() {
        val failure = PlaywrightException("Browser process failed")
        var installs = 0
        var launches = 0
        assertSame(failure, assertFailsWith<PlaywrightException> {
            launchManagedChromium({ launches++; throw failure }, { installs++ })
        })
        assertEquals(1, launches)
        assertEquals(0, installs)
    }

    @Test fun failedInstallationDoesNotRetryLaunch() {
        var launches = 0
        val failure = BrowserInstallFailed("Installation failed")
        assertSame(failure, assertFailsWith<BrowserInstallFailed> {
            launchManagedChromium({
                launches++
                throw PlaywrightException("Executable doesn't exist at /private/browser")
            }, { throw failure })
        })
        assertEquals(1, launches)
    }

    @Test fun missingBrowserAfterInstallationHasAnActionableError() {
        val error = assertFailsWith<BrowserInstallFailed> {
            launchManagedChromium<String>({
                throw PlaywrightException("Executable doesn't exist at /private/browser")
            }, {})
        }
        kotlin.test.assertContains(error.message.orEmpty(), "права доступа")
    }
}
