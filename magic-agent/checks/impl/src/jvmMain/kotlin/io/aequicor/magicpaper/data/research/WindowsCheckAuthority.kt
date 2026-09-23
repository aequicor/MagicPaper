package io.aequicor.magicpaper.data.research

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
internal data class WindowsCheckLabelSnapshot(val path: String, val fileIdentity: String, val label: String)
@Serializable
internal data class WindowsCheckLabelReceipt(val receiptId: String,
    val roots: List<String>, val originals: List<WindowsCheckLabelSnapshot>)

/**
 * Captures the integrity label of the entire affected tree before any of it is lowered for the run.
 * Lowering, not an ACL grant, is what lets a low-integrity child write its artifacts: the project and a
 * real `.git` keep their medium label, so the child cannot write up into them.
 */
internal class WindowsCheckAuthority private constructor(
    val receiptId: String, private val roots: List<Path>,
    private val targets: List<Target>,
) : AutoCloseable {
    private var mayHaveLowered = false
    /** One inheritable low label per root: existing children receive it, and so do artifacts created later. */
    fun lower() {
        mayHaveLowered = true // a failing Win32 call is not evidence that no label changed
        roots.forEach { root -> targets.single { it.path == root }.lower() }
    }
    fun restoreAndConfirm(): String {
        var failure: Throwable? = null
        fun attempt(block: () -> Unit) { try { block() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        if (mayHaveLowered) {
            // Parents first: a restored label re-propagates, so a child's own restoration stays exact.
            targets.forEach { target -> attempt { target.restore() } }
            targets.forEach { target -> attempt {
                check(labelRestored(target.snapshot.label, target.current())) { "Original check label was not restored exactly" }
            } }
            // A path the command created inherited the lowered label and has no snapshot of its own.
            // Restoring the roots re-propagates to everything below them, so this loop is a proof and not a
            // cleanup: a borrowed label that survived must fail the run instead of staying in the user's
            // tree. Removing a label from an artifact individually is not a no-op either — the file then
            // stops inheriting the next run's label and the following check cannot overwrite it, which
            // ResearchSandboxNativeTest.repeatChecksPreserveNewIgnoredFilesAndReleaseTemporaryCaches pins.
            val captured = targets.associate { it.path to it.snapshot.label }
            roots.forEach { root -> attempt { paths(root).forEach { path -> Target.open(path).use { artifact ->
                check(labelRestored(captured[path].orEmpty(), artifact.current())) { "Check label remains on an artifact" }
            } } } }
        }
        failure?.let { throw it }
        return "label-restored:$receiptId"
    }
    override fun close() {
        var failure: Throwable? = null
        targets.asReversed().forEach { try { it.close() } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) } }
        failure?.let { throw it }
    }

    private class Target(val path: Path, val handle: Pointer, val snapshot: WindowsCheckLabelSnapshot) : AutoCloseable {
        fun current(): String = label(handle)
        fun lower() = writeLabel(handle, WINDOWS_LOW_LABEL)
        fun restore() {
            // Restore on the retained original handle even if another process replaced its path.
            // The path is checked afterward and a replacement never yields restoration proof.
            writeLabel(handle, snapshot.label.ifBlank { null })
            open(path).use { check(it.snapshot.fileIdentity == snapshot.fileIdentity) { "Check label path identity changed" } }
        }
        override fun close() { check(kernel.getFunction("CloseHandle").invokeInt(arrayOf(handle)) != 0) { "Cannot close check label handle" } }
        companion object {
            fun open(path: Path): Target {
                check(!WindowsResearchSandbox.unsafeLink(path)) { "Check authority cannot follow a link" }
                // READ_CONTROL reads the label, WRITE_OWNER is what lowering an object the caller owns requires.
                val handle = kernel.getFunction("CreateFileW").invokePointer(arrayOf(WString(path.toString()), 0x000A0080, 7, null, 3, 0x02200000, null))
                check(handle != null && Pointer.nativeValue(handle) != -1L) { "Cannot open check label object" }
                try {
                    val identity = Memory(24).use { info ->
                        check(kernel.getFunction("GetFileInformationByHandleEx").invokeInt(arrayOf(handle, 18, info, 24)) != 0) { "Cannot read check file identity" }
                        info.getByteArray(0, 24).joinToString("") { "%02x".format(it) }
                    }
                    return Target(path, handle, WindowsCheckLabelSnapshot(path.toString(), identity, label(handle)))
                } catch (error: Throwable) { if (kernel.getFunction("CloseHandle").invokeInt(arrayOf(handle)) == 0) error.addSuppressed(IllegalStateException("Cannot close check label handle")); throw error }
            }
        }
    }
    companion object {
        private val kernel by lazy { NativeLibrary.getInstance("kernel32") }
        private val advapi by lazy { NativeLibrary.getInstance("advapi32") }
        private const val SE_FILE_OBJECT = 1
        private const val LABEL_INFORMATION = 0x10
        private const val SDDL_REVISION = 1
        private fun bool(name: String, vararg args: Any?) { check(advapi.getFunction(name).invokeInt(args) != 0) { "Check label operation failed: $name (${Native.getLastError()})" } }
        private fun free(value: Pointer?) { if (value != null) kernel.getFunction("LocalFree").invokePointer(arrayOf(value)) }

        /** The object's integrity label as SDDL, or an empty string while it carries none. */
        private fun label(handle: Pointer): String {
            val descriptor = PointerByReference(); val sacl = PointerByReference()
            check(advapi.getFunction("GetSecurityInfo").invokeInt(arrayOf(handle, SE_FILE_OBJECT, LABEL_INFORMATION,
                null, null, null, sacl, descriptor)) == 0) { "Cannot read check integrity label (${Native.getLastError()})" }
            try {
                val present = IntByReference(); val defaulted = IntByReference()
                if (advapi.getFunction("GetSecurityDescriptorSacl").invokeInt(arrayOf(descriptor.value, present, sacl, defaulted)) == 0 ||
                    present.value == 0 || sacl.value == null) return ""
                val text = PointerByReference()
                check(advapi.getFunction("ConvertSecurityDescriptorToStringSecurityDescriptorW")
                    .invokeInt(arrayOf(descriptor.value, SDDL_REVISION, LABEL_INFORMATION, text, null)) != 0) {
                    "Cannot render check integrity label (${Native.getLastError()})"
                }
                try { return text.value.getWideString(0) } finally { free(text.value) }
            } finally { free(descriptor.value) }
        }

        /** Writes one integrity label; a null SACL removes it and returns the object to its default level. */
        private fun writeLabel(handle: Pointer, sddl: String?) {
            if (sddl == null) { setLabel(handle, null); return }
            val reference = PointerByReference()
            bool("ConvertStringSecurityDescriptorToSecurityDescriptorW", WString(sddl), SDDL_REVISION, reference, null)
            try {
                val sacl = PointerByReference(); val present = IntByReference(); val defaulted = IntByReference()
                bool("GetSecurityDescriptorSacl", reference.value, present, sacl, defaulted)
                check(present.value != 0 && sacl.value != null) { "Check integrity label is incomplete" }
                setLabel(handle, sacl.value)
            } finally { free(reference.value) }
        }

        private fun setLabel(handle: Pointer, sacl: Pointer?) {
            check(advapi.getFunction("SetSecurityInfo").invokeInt(arrayOf(handle, SE_FILE_OBJECT, LABEL_INFORMATION,
                null, null, null, sacl)) == 0) { "Cannot write check integrity label (${Native.getLastError()})" }
        }

        private fun paths(root: Path): List<Path> = Files.walk(root).use { stream ->
            stream.limit(100_001).toList().also { check(it.size <= 100_000) { "Check label tree is too large" } }
        }
        /**
         * Writing a label makes the Windows resource manager recompute its inheritance bookkeeping: the
         * descriptor gains `AI`, and a label a child received from its parent reads back with the `ID` ACE
         * flag. Neither carries access of its own, and requiring byte equality there made restoration
         * unprovable on every artifact — one lowered label then refused all sandboxed checks for the whole
         * visit. `P` still matters, since it decides whether children keep inheriting the label.
         */
        fun labelRestored(snapshot: String, current: String): Boolean =
            effectiveLabel(snapshot) == effectiveLabel(current)

        private val inheritedMarker = Regex("ID(?=[;)])")
        private fun effectiveLabel(sddl: String): String {
            if (sddl.isBlank()) return ""
            require(sddl.startsWith("S:")) { "Ожидается SDDL метки целостности" }
            val body = sddl.removePrefix("S:")
            val headerEnd = body.indexOf('(').let { if (it < 0) body.length else it }
            val header = body.substring(0, headerEnd).replace("AI", "").replace("AR", "")
            return header + inheritedMarker.replace(body.substring(headerEnd), "")
        }
        fun capture(roots: List<Path>, receiptId: String, recorder: CheckAuthorityRecorder): WindowsCheckAuthority {
            val targets = mutableListOf<Target>()
            try {
                roots.flatMap(::paths).distinct().sortedBy { it.nameCount }.forEach { targets += Target.open(it) }
                val bundle = WindowsCheckLabelReceipt(receiptId, roots.map(Path::toString), targets.map { it.snapshot })
                val savedId = recorder(receiptId, Json.encodeToString(WindowsCheckLabelReceipt.serializer(), bundle).encodeToByteArray())
                check(savedId.isNotBlank()) { "Original check label was not durably recorded" }
                return WindowsCheckAuthority(savedId, roots, targets)
            } catch (error: Throwable) {
                targets.asReversed().forEach { try { it.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) } }
                throw error
            }
        }
    }
}

/** Low mandatory level, inheritable, no-write-up: the label a run borrows for its artifact directories. */
internal const val WINDOWS_LOW_LABEL = "S:(ML;OICI;NW;;;LW)"
/** The same level without inheritance: the private desktop exists only for this one child. */
internal const val WINDOWS_LOW_LABEL_DESKTOP = "S:(ML;;NW;;;LW)"
