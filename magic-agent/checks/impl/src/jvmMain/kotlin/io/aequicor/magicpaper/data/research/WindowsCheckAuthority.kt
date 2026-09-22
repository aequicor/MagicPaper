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
internal data class WindowsCheckAclSnapshot(val path: String, val fileIdentity: String, val sddl: String)
@Serializable
internal data class WindowsCheckAclReceipt(val receiptId: String, val restrictingSid: String,
    val roots: List<String>, val originals: List<WindowsCheckAclSnapshot>)

/** Captures the entire affected tree before SetSecurityInfo can propagate the run ACE to existing children. */
internal class WindowsCheckAuthority private constructor(
    val receiptId: String, private val roots: List<Path>, private val sidText: String,
    private val targets: List<Target>,
) : AutoCloseable {
    private var mayHaveGranted = false
    /** Takes the SID text, not a raw pointer: this owner allocates and frees the native SID it grants. */
    fun grant(sid: String) {
        mayHaveGranted = true // a failing Win32 call is not evidence that no ACE changed
        val native = PointerByReference()
        bool("ConvertStringSidToSidW", WString(sid), native)
        try { grantSid(native.value) } finally { free(native.value) }
    }
    private fun grantSid(sid: Pointer) {
        roots.forEach { root ->
            val target = targets.single { it.path == root }
            target.withDescriptor { _, dacl ->
                val next = PointerByReference()
                try {
                    Memory(48).use { access ->
                        access.clear(); access.setInt(0, 0x001F01FF); access.setInt(4, 1); access.setInt(8, 3)
                        access.setInt(28, 0); access.setInt(32, 5); access.setPointer(40, sid)
                        check(advapi.getFunction("SetEntriesInAclW").invokeInt(arrayOf(1, access, dacl, next)) == 0) { "Cannot prepare check ACL" }
                    }
                    check(advapi.getFunction("SetSecurityInfo").invokeInt(arrayOf(target.handle, 1, 4, null, null, next.value, null)) == 0) {
                        "Cannot grant check directory authority"
                    }
                } finally { free(next.value) }
            }
        }
    }
    fun restoreAndConfirm(): String {
        var failure: Throwable? = null
        fun attempt(block: () -> Unit) { try { block() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        if (mayHaveGranted) {
            // Parents first: propagation cannot overwrite a child's exact original ACL restored afterward.
            targets.forEach { target -> attempt { target.restore() } }
            targets.forEach { target -> attempt { check(target.current() == target.snapshot.sddl) { "Original check ACL was not restored exactly" } } }
            // A path the command created inherited the run ACE and has no snapshot to return to. Its only
            // correct state is the inheritance of its already restored parent, so the run ACE is revoked
            // from it; without that step any artifact made the restoration proof unattainable.
            val captured = targets.mapTo(HashSet()) { it.path }
            val runSid = PointerByReference()
            attempt { bool("ConvertStringSidToSidW", WString(sidText), runSid) }
            try {
                roots.forEach { root -> attempt { paths(root).forEach { path -> Target.open(path).use { target ->
                    if (path !in captured && runSid.value != null) attempt { target.revoke(runSid.value) }
                    check(sidText !in target.current()) { "Check authority remains on an artifact" }
                } } } }
            } finally { free(runSid.value) }
        }
        failure?.let { throw it }
        return "acl-restored:$receiptId"
    }
    override fun close() {
        var failure: Throwable? = null
        targets.asReversed().forEach { try { it.close() } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) } }
        failure?.let { throw it }
    }

    private class Target(val path: Path, val handle: Pointer, val snapshot: WindowsCheckAclSnapshot) : AutoCloseable {
        fun <T> withDescriptor(block: (Pointer, Pointer?) -> T): T {
            val descriptor = PointerByReference(); val dacl = PointerByReference()
            check(advapi.getFunction("GetSecurityInfo").invokeInt(arrayOf(handle, 1, 4, null, null, dacl, null, descriptor)) == 0) { "Cannot read original check ACL" }
            try { return block(descriptor.value, dacl.value) } finally { free(descriptor.value) }
        }
        fun current(): String = withDescriptor { descriptor, _ -> sddl(descriptor) }
        /** Drops every ACE of one SID. Used for paths created during the run, which have no snapshot. */
        fun revoke(sid: Pointer) = withDescriptor { descriptor, dacl ->
            val rebuilt = withoutSid(checkNotNull(dacl) { "Check ACL is missing" }, sid)
            if (rebuilt != null) try { write(descriptor, rebuilt) } finally { free(rebuilt) }
        }
        /** Rebuilds a DACL without any ACE of one SID, or returns null when it holds none.
         *  `SetEntriesInAcl(REVOKE_ACCESS)` rewrites explicit entries only and silently leaves the inherited
         *  run ACE an artifact picked up from its parent — exactly the ACE that must not survive. */
        private fun withoutSid(acl: Pointer, sid: Pointer): Pointer? {
            val info = Memory(ACL_SIZE_INFORMATION_BYTES.toLong()).also { it.clear() }
            check(advapi.getFunction("GetAclInformation").invokeInt(arrayOf(acl, info, ACL_SIZE_INFORMATION_BYTES, ACL_SIZE_INFORMATION)) != 0) {
                "Cannot size check ACL"
            }
            val revision = acl.getByte(0).toInt()
            val ace = PointerByReference()
            val kept = mutableListOf<Pair<Pointer, Int>>()
            var removed = false
            repeat(info.getInt(0)) { index ->
                check(advapi.getFunction("GetAce").invokeInt(arrayOf(acl, index, ace)) != 0) { "Cannot read check ACL entry" }
                val entry = checkNotNull(ace.value) { "Check ACL entry is missing" }
                val size = entry.getShort(2).toInt() and 0xFFFF
                if (advapi.getFunction("EqualSid").invokeInt(arrayOf(entry.share(ACE_SID_OFFSET), sid)) != 0) removed = true
                else kept += entry to size
            }
            if (!removed) return null
            val bytes = ACL_HEADER_BYTES + kept.sumOf { it.second }
            val rebuilt = Memory(bytes.toLong()).also { it.clear() }
            check(advapi.getFunction("InitializeAcl").invokeInt(arrayOf(rebuilt, bytes, revision)) != 0) { "Cannot rebuild check ACL" }
            kept.forEach { (entry, size) ->
                // MAXDWORD appends, so the surviving entries keep the order the original ACL had.
                check(advapi.getFunction("AddAce").invokeInt(arrayOf(rebuilt, revision, -1, entry, size)) != 0) { "Cannot keep check ACL entry" }
            }
            return rebuilt
        }
        /** SetSecurityInfo would rebuild the descriptor and stamp SE_DACL_AUTO_INHERITED on it, so the merged
         *  DACL goes into a fresh descriptor carrying this object's own control flags instead. */
        private fun write(original: Pointer, dacl: Pointer) {
            val descriptor = Memory(SECURITY_DESCRIPTOR_MIN_LENGTH).also { it.clear() }
            check(advapi.getFunction("InitializeSecurityDescriptor").invokeInt(arrayOf(descriptor, 1)) != 0) { "Cannot initialize check ACL descriptor" }
            Memory(2).use { control ->
                check(advapi.getFunction("GetSecurityDescriptorControl").invokeInt(arrayOf(original, control, IntByReference())) != 0) { "Cannot read check ACL control" }
                val retained = control.getShort(0).toInt() and CONTROL_MASK
                check(advapi.getFunction("SetSecurityDescriptorControl").invokeInt(arrayOf(descriptor, CONTROL_MASK, retained)) != 0) { "Cannot carry over check ACL control" }
            }
            check(advapi.getFunction("SetSecurityDescriptorDacl").invokeInt(arrayOf(descriptor, true, dacl, false)) != 0) { "Cannot attach revoked check ACL" }
            check(advapi.getFunction("SetKernelObjectSecurity").invokeInt(arrayOf(handle, DACL_SECURITY_INFORMATION, descriptor)) != 0) {
                "Cannot write check ACL (${Native.getLastError()})"
            }
        }
        fun restore() {
            // Revoke on the retained original handle even if another process replaced its path.
            // The path is checked afterward and a replacement never yields restoration proof.
            val descriptor = PointerByReference()
            bool("ConvertStringSecurityDescriptorToSecurityDescriptorW", WString(snapshot.sddl), 1, descriptor, null)
            try {
                val dacl = PointerByReference(); val present = IntByReference(); val defaulted = IntByReference()
                bool("GetSecurityDescriptorDacl", descriptor.value, present, dacl, defaulted)
                check(present.value != 0 && dacl.value != null) { "Original check ACL is incomplete" }
                // SetSecurityInfo rebuilds a descriptor from the DACL alone and stamps
                // SE_DACL_AUTO_INHERITED on every unprotected write, so it can never return an object
                // whose snapshot lacked that flag: the ACE bytes came back identical while the ACL read
                // back as "D:AI(...)" and restoration proof was refused forever. The descriptor parsed
                // from the snapshot SDDL carries the whole SECURITY_DESCRIPTOR_CONTROL — protection and
                // auto-inheritance included — so it is written back verbatim through the same handle.
                check(advapi.getFunction("SetKernelObjectSecurity").invokeInt(arrayOf(handle, DACL_SECURITY_INFORMATION, descriptor.value)) != 0) {
                    "Cannot restore original check ACL (${Native.getLastError()})"
                }
            } finally { free(descriptor.value) }
            open(path).use { check(it.snapshot.fileIdentity == snapshot.fileIdentity) { "Check ACL path identity changed" } }
        }
        override fun close() { check(kernel.getFunction("CloseHandle").invokeInt(arrayOf(handle)) != 0) { "Cannot close check ACL handle" } }
        companion object {
            fun open(path: Path): Target {
                check(!WindowsResearchSandbox.unsafeLink(path)) { "Check authority cannot follow a link" }
                val handle = kernel.getFunction("CreateFileW").invokePointer(arrayOf(WString(path.toString()), 0x00060080, 7, null, 3, 0x02200000, null))
                check(handle != null && Pointer.nativeValue(handle) != -1L) { "Cannot open check ACL object" }
                try {
                    val identity = Memory(24).use { info ->
                        check(kernel.getFunction("GetFileInformationByHandleEx").invokeInt(arrayOf(handle, 18, info, 24)) != 0) { "Cannot read check file identity" }
                        info.getByteArray(0, 24).joinToString("") { "%02x".format(it) }
                    }
                    val descriptor = PointerByReference(); val dacl = PointerByReference()
                    check(advapi.getFunction("GetSecurityInfo").invokeInt(arrayOf(handle, 1, 4, null, null, dacl, null, descriptor)) == 0) { "Cannot snapshot check ACL" }
                    val text = try { check(dacl.value != null) { "Check directory requires an explicit ACL" }; sddl(descriptor.value) } finally { free(descriptor.value) }
                    return Target(path, handle, WindowsCheckAclSnapshot(path.toString(), identity, text))
                } catch (error: Throwable) { if (kernel.getFunction("CloseHandle").invokeInt(arrayOf(handle)) == 0) error.addSuppressed(IllegalStateException("Cannot close check ACL handle")); throw error }
            }
        }
    }
    companion object {
        /** SECURITY_INFORMATION selecting only the DACL of a whole security descriptor. */
        private const val DACL_SECURITY_INFORMATION = 4
        /** GetAclInformation class returning ACE count and ACL byte usage: three DWORDs. */
        private const val ACL_SIZE_INFORMATION = 2
        private const val ACL_SIZE_INFORMATION_BYTES = 12
        /** ACL header, and the offset of an ACE's SID behind its 4-byte header and 4-byte access mask. */
        private const val ACL_HEADER_BYTES = 8
        private const val ACE_SID_OFFSET = 8L
        /** Fixed header size InitializeSecurityDescriptor fills in (SECURITY_DESCRIPTOR_MIN_LENGTH). */
        private const val SECURITY_DESCRIPTOR_MIN_LENGTH = 64L
        /** SE_DACL_PROTECTED and SE_DACL_AUTO_INHERITED: the DACL control flags an ACL write may change. */
        private const val CONTROL_MASK = 0x1000 or 0x0400
        private val kernel by lazy { NativeLibrary.getInstance("kernel32") }
        private val advapi by lazy { NativeLibrary.getInstance("advapi32") }
        private fun bool(name: String, vararg args: Any?) { check(advapi.getFunction(name).invokeInt(args) != 0) { "Check ACL operation failed: $name (${Native.getLastError()})" } }
        private fun free(value: Pointer?) { if (value != null) kernel.getFunction("LocalFree").invokePointer(arrayOf(value)) }
        private fun sddl(descriptor: Pointer): String {
            val text = PointerByReference()
            bool("ConvertSecurityDescriptorToStringSecurityDescriptorW", descriptor, 1, 4, text, null)
            try { return text.value.getWideString(0) } finally { free(text.value) }
        }
        private fun paths(root: Path): List<Path> = Files.walk(root).use { stream ->
            stream.limit(100_001).toList().also { check(it.size <= 100_000) { "Check ACL tree is too large" } }
        }
        fun capture(roots: List<Path>, sidText: String, receiptId: String, recorder: CheckAuthorityRecorder): WindowsCheckAuthority {
            val targets = mutableListOf<Target>()
            try {
                roots.flatMap(::paths).distinct().sortedBy { it.nameCount }.forEach { targets += Target.open(it) }
                val bundle = WindowsCheckAclReceipt(receiptId, sidText, roots.map(Path::toString), targets.map { it.snapshot })
                val savedId = recorder(receiptId, Json.encodeToString(WindowsCheckAclReceipt.serializer(), bundle).encodeToByteArray())
                check(savedId.isNotBlank()) { "Original check ACL was not durably recorded" }
                return WindowsCheckAuthority(savedId, roots, sidText, targets)
            } catch (error: Throwable) {
                targets.asReversed().forEach { try { it.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) } }
                throw error
            }
        }
    }
}
