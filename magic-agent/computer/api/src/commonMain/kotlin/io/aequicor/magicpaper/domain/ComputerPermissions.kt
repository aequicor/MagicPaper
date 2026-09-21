package io.aequicor.magicpaper.domain

/** Onboarding only: no invocation authority, input, capture, window discovery or consent prompts. */
interface ComputerPermissions {
    suspend fun inspect(computer: ComputerAccess, application: ComputerAccess): ComputerPermissionReport
    suspend fun openSettings(permission: ComputerPermission)
    /** Only a target returned by this adapter may be revealed; never execute the file. */
    suspend fun reveal(target: PermissionTarget)
}

enum class PermissionPlatform { MACOS, WINDOWS, OTHER }
enum class ComputerPermission { SCREEN_RECORDING, ACCESSIBILITY }
data class PermissionTarget(val label: String, val path: String)
data class PermissionCheck(val permission: ComputerPermission, val target: PermissionTarget, val granted: Boolean)
data class ComputerPermissionReport(
    val platform: PermissionPlatform = PermissionPlatform.OTHER,
    val checks: List<PermissionCheck> = emptyList(),
)
