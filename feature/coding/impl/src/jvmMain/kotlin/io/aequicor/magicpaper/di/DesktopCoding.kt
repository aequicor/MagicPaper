package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.coding.DesktopCodingRuntime
import io.aequicor.magicpaper.data.coding.PiCodingRuntime
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*

/** Subscription token handoff remains within the native engine module. */
fun createDesktopCodingRuntime(
    computer: io.aequicor.magicpaper.data.computer.DesktopComputerUse,
    subscription: CodexAppServerOpenAiSubscription,
    skillSelection: suspend (String) -> CodingSkillSelection,
    recordSkillRun: suspend (CodingSkillRunRecord) -> Unit,
    runObserver: CodingRunObserver,
): DesktopCodingRuntime = DesktopCodingRuntime(
    PiCodingRuntime(computerUse = computer, subscriptionToken = subscription::subscriptionAccessToken,
        browserAvailability = subscription.browserAvailability),
    subscription,
    skillSelection = skillSelection,
    recordSkillRun = recordSkillRun,
    runObserver = runObserver,
)
