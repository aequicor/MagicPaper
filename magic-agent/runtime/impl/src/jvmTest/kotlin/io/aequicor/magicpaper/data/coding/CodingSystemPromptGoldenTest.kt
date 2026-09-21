package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Captured before common prompt extraction; detects changes to content and section ordering. */
class CodingSystemPromptGoldenTest {
    @Test fun nativePromptTextPreservesAllEngineModeAndCapabilityCombinations() {
        val rows = buildList {
            for (engine in CodingEngine.entries + null) for (mode in listOf("code", "planning", "research")) {
                for (speed in listOf(false, true)) for (browser in listOf(false, true)) {
                    val flags = FeatureFlagState().with(FeatureFlag.AGENT_SPEED_BOOST, speed)
                    val prompt = codingSystemPrompt(engine, mode == "planning", "PROJECT RULES", mode == "research",
                        featureFlags = flags, browserAvailable = browser)
                    val digest = MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray()).joinToString("") { "%02x".format(it) }
                    add("${engine?.name ?: "NONE"}/$mode/$speed/$browser=$digest")
                }
            }
        }
        assertEquals(expected.trimIndent().lines(), rows)
    }

    private val expected = """
        PI/code/false/false=634183bdce297d6b3e43edcf44c37914e00510c76d4edd0e5aaba29cd8577e9b
        PI/code/false/true=812e77f744ff166afe16a5f122888fb346ed7958f180357f8cbc184271162bf3
        PI/code/true/false=922edce0e2fda26192391170e881f0f2af55a25c27a755d9813bf27f5bb74b3d
        PI/code/true/true=ed562825173918acf43b0c9fd1ea9a53e40e10bc38e6f111cd707b55d2442de1
        PI/planning/false/false=3e9d149ee521d4da19c584afcbd91eb78d1dadf856ce07fb3aa09c71a00b074b
        PI/planning/false/true=75fd38cf93c0f89131ca83aa6bff7e233652572e92e0e217ab23879948481411
        PI/planning/true/false=3e9d149ee521d4da19c584afcbd91eb78d1dadf856ce07fb3aa09c71a00b074b
        PI/planning/true/true=75fd38cf93c0f89131ca83aa6bff7e233652572e92e0e217ab23879948481411
        PI/research/false/false=27a5c6a30c6397a2ef081eb4d47447e607174a88d923ddfab500545b532e1a5a
        PI/research/false/true=364dbf3054f76722a156f3a79776abb98394f77c5829a735d84544df59629948
        PI/research/true/false=d2aece723dd4e668724cbe1ecc797bbe6cccb77d5526c333e0a2f281959be8a9
        PI/research/true/true=5eef04d574fa1c0eef707a3fb62fc1498ffd0872be9a133af4ae2104a4a1ae0e
        CODEX/code/false/false=20699b79e4f5f6b02e73a7d527ba62b5b7141f699b8f7fa94a884f69b90edb18
        CODEX/code/false/true=e599e8a89e0676cc6922fa60dd5a99a47dd38183bc8749b18977276bad3ba985
        CODEX/code/true/false=676ef304f8c5eee0051f51ce1e71618e0e35b4003e4dbb1aeda27335e7f15b89
        CODEX/code/true/true=3847d80c5f4cf0003b77cf55a371178ee7b27a6d758c6892716201d45327670c
        CODEX/planning/false/false=3e9d149ee521d4da19c584afcbd91eb78d1dadf856ce07fb3aa09c71a00b074b
        CODEX/planning/false/true=75fd38cf93c0f89131ca83aa6bff7e233652572e92e0e217ab23879948481411
        CODEX/planning/true/false=3e9d149ee521d4da19c584afcbd91eb78d1dadf856ce07fb3aa09c71a00b074b
        CODEX/planning/true/true=75fd38cf93c0f89131ca83aa6bff7e233652572e92e0e217ab23879948481411
        CODEX/research/false/false=27a5c6a30c6397a2ef081eb4d47447e607174a88d923ddfab500545b532e1a5a
        CODEX/research/false/true=364dbf3054f76722a156f3a79776abb98394f77c5829a735d84544df59629948
        CODEX/research/true/false=d2aece723dd4e668724cbe1ecc797bbe6cccb77d5526c333e0a2f281959be8a9
        CODEX/research/true/true=5eef04d574fa1c0eef707a3fb62fc1498ffd0872be9a133af4ae2104a4a1ae0e
        NONE/code/false/false=7f6bdf15c970c469b3cd0514f93b4d2c2e6de46b9821f3239d4145c8679bafa0
        NONE/code/false/true=b85ca1c65d892f3ceb33b61c10c3502c7a568a7dc0d3d609615a28c6b1df6607
        NONE/code/true/false=7f6bdf15c970c469b3cd0514f93b4d2c2e6de46b9821f3239d4145c8679bafa0
        NONE/code/true/true=b85ca1c65d892f3ceb33b61c10c3502c7a568a7dc0d3d609615a28c6b1df6607
        NONE/planning/false/false=3e9d149ee521d4da19c584afcbd91eb78d1dadf856ce07fb3aa09c71a00b074b
        NONE/planning/false/true=75fd38cf93c0f89131ca83aa6bff7e233652572e92e0e217ab23879948481411
        NONE/planning/true/false=3e9d149ee521d4da19c584afcbd91eb78d1dadf856ce07fb3aa09c71a00b074b
        NONE/planning/true/true=75fd38cf93c0f89131ca83aa6bff7e233652572e92e0e217ab23879948481411
        NONE/research/false/false=27a5c6a30c6397a2ef081eb4d47447e607174a88d923ddfab500545b532e1a5a
        NONE/research/false/true=364dbf3054f76722a156f3a79776abb98394f77c5829a735d84544df59629948
        NONE/research/true/false=d2aece723dd4e668724cbe1ecc797bbe6cccb77d5526c333e0a2f281959be8a9
        NONE/research/true/true=5eef04d574fa1c0eef707a3fb62fc1498ffd0872be9a133af4ae2104a4a1ae0e
    """
}
