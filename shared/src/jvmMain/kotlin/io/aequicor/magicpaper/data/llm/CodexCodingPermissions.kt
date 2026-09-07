package io.aequicor.magicpaper.data.llm

import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.*

/** Build caches are pre-granted; engine approval requests are routed to the user. */
internal class CodexCodingPermissions(
    project: Path,
    userHome: Path = Paths.get(System.getProperty("user.home")),
    gradleUserHome: String? = System.getenv("GRADLE_USER_HOME"),
) {
    private val projectRoot = project.toFile().canonicalFile.toPath()
    private val home = userHome.toFile().canonicalFile.toPath()
    private val gradleRoot = gradleUserHome?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        ?.takeIf { it.isAbsolute } ?: home.resolve(".gradle")
    val writableRoots: List<String> = buildList {
        add(projectRoot.toString())
        val cache = gradleRoot.toFile().canonicalFile.toPath()
        // A misconfigured environment variable or symlink must not grant the entire home/disk.
        if (!home.startsWith(cache) && !projectRoot.startsWith(cache)) add(cache.toString())
    }.distinct()

    fun JsonObjectBuilder.approvals() {
        put("approvalPolicy", "on-request")
        put("approvalsReviewer", "user")
    }

    fun sandboxPolicy(): JsonObject = buildJsonObject {
        put("type", "workspaceWrite")
        put("networkAccess", false)
        put("writableRoots", JsonArray(writableRoots.map(::JsonPrimitive)))
    }

    fun threadConfig(): JsonObject = buildJsonObject {
        put("sandbox_workspace_write.writable_roots", JsonArray(writableRoots.map(::JsonPrimitive)))
        put("sandbox_workspace_write.network_access", false)
    }
}
