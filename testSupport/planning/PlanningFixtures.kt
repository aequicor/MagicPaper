package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json

/** Explicit text fixture for plan validation/UI tests; production never falls back to this adapter. */
fun textPlanComposer(gateway: LlmGateway, json: Json = Json { ignoreUnknownKeys = true }, searchEngine: SearchEngine? = null, retryLimit: Int? = 2) =
    PlanComposer(gateway, json, searchEngine, planningGateway = object : PlanningGateway {
        override suspend fun completeWithActivity(project: CodingProject, engine: CodingEngine, requestId: String,
            profile: LlmProfile, messages: List<LlmMessage>, onActivity: (CodingStep) -> Unit) =
            gateway.completeWithActivity(profile, messages, onActivity)
    }, projectLookup = { CodingProject(it, it, "/fixture", 0) }, retryLimit = { retryLimit })
