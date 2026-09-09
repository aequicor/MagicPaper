package io.aequicor.magicpaper.data.research

import kotlinx.serialization.json.*

internal object ResearchCheckTool {
    const val instructions = "Run a build, test or diagnostic command with OS-enforced source protection. command is an executable followed by individual arguments; cwd is a relative project subdirectory. Only host-selected artifact directories and isolated caches are writable. No write permissions or escalation can be requested. A blocked check must not be retried outside this tool. Dependencies must already be available; do not run installers, migrations or formatters."
    val schema = Json.parseToJsonElement("""{
      "type":"object","additionalProperties":false,"required":["command"],"properties":{
        "command":{"type":"array","minItems":1,"maxItems":128,"items":{"type":"string"}},
        "cwd":{"type":"string","description":"Relative project subdirectory, default ."}
      }}""").jsonObject
    val definition = buildJsonObject {
        put("name", "research_check"); put("description", instructions); put("inputSchema", schema)
        putJsonObject("annotations") { put("readOnlyHint", false); put("destructiveHint", false); put("openWorldHint", false) }
    }
    fun result(value: ResearchCheckResult) = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject {
            put("type", "text"); put("text", value.blockedReason ?: "Код завершения: ${value.exitCode}\n${value.output}")
        }) }
        put("isError", value.blockedReason != null || value.exitCode != 0)
    }
}
