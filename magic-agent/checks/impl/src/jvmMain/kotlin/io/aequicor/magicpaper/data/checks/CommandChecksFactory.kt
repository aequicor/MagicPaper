package io.aequicor.magicpaper.data.checks

import io.aequicor.magicpaper.data.research.SandboxCheckDriver
import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.checks.CommandChecks
import java.nio.file.Path
import java.nio.file.Paths

fun createCommandChecks(events: EventJournal, payloads: KeyValueStore,
    root: Path = Paths.get(System.getProperty("user.home"), ".MagicPaper", "research-checks"),
    timeoutMillis: Long = 15 * 60_000L,
    probeRetryDelayMs: Long = DefaultCommandChecks.PROBE_RETRY_DELAY_MS): CommandChecks =
    DefaultCommandChecks(events, payloads, SandboxCheckDriver(root, timeoutMillis), probeRetryDelayMs)
