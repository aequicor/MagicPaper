package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

internal val noRuntimeQuestionnaires = MutableStateFlow<List<UserInteractionRequest>>(emptyList()).asStateFlow()
internal val noCodingApprovals = MutableStateFlow<List<CodingApproval>>(emptyList()).asStateFlow()
