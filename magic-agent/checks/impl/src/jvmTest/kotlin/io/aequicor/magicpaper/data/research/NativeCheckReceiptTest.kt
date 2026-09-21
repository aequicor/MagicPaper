package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.domain.checks.CheckProcessReceipt
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NativeCheckReceiptTest {
    private inline fun fixture(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("native-check-receipt-")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
    @Test fun missingCleanupIsUnknownEvenWhenAnIdentityAndDeadPidExist() = fixture { root ->
        val receipt = CheckProcessReceipt("attempt", "fixture", Long.MAX_VALUE)
        NativeCheckReceiptFiles(root, receipt.id).record(NativeCheckIdentity(receipt, 123, "group"))
        assertNull(readNativeCheckCleanup(receipt, root))
        assertNull(readNativeCheckCleanup(receipt.copy(id = "missing"), root))
    }
    @Test fun completionSurvivesReopenAndCannotBeReboundToAnotherProcessOrProof() = fixture { root ->
        val receipt = CheckProcessReceipt("attempt", "fixture", 123, "acl")
        val files = NativeCheckReceiptFiles(root, receipt.id)
        files.record(NativeCheckIdentity(receipt, 456, "group", 789, 1000))
        val proof = NativeCheckCleanup("group-stopped", "acl-restored")
        assertEquals(proof, files.confirm(receipt, proof))
        assertEquals(proof, readNativeCheckCleanup(receipt, root))
        assertEquals(proof, NativeCheckReceiptFiles(root, receipt.id).confirm(receipt, proof))
        assertFails { readNativeCheckCleanup(receipt.copy(pid = 124), root) }
        assertFails { files.confirm(receipt, proof.copy(groupProof = "changed")) }
        assertFails { files.record(NativeCheckIdentity(receipt, 457, "group")) }
    }
    @Test fun corruptMissingOrSubstitutedIdentityNeverConfirmsCleanup() = fixture { root ->
        val receipt = CheckProcessReceipt("attempt", "fixture", 123)
        val files = NativeCheckReceiptFiles(root, receipt.id)
        files.record(NativeCheckIdentity(receipt, 456, "group"))
        files.confirm(receipt, NativeCheckCleanup("stopped", "unchanged"))
        val identity = Files.list(root).use { it.filter { path -> !path.fileName.toString().contains(".cleanup.") }.findFirst().orElseThrow() }
        val original = Files.readString(identity)
        Files.writeString(identity, "{truncated")
        assertFails { readNativeCheckCleanup(receipt, root) }
        Files.writeString(identity, original.replace("456", "457"))
        assertFails { readNativeCheckCleanup(receipt, root) }
        Files.delete(identity)
        assertFails { readNativeCheckCleanup(receipt, root) }
    }
    @Test fun tornCleanupFileIsNotTreatedAsAbsent() = fixture { root ->
        val receipt = CheckProcessReceipt("attempt", "fixture", 123)
        val files = NativeCheckReceiptFiles(root, receipt.id)
        files.record(NativeCheckIdentity(receipt, 456, "group"))
        files.confirm(receipt, NativeCheckCleanup("stopped", "unchanged"))
        val proof = Files.list(root).use { it.filter { path -> path.fileName.toString().contains(".cleanup.") }.findFirst().orElseThrow() }
        Files.writeString(proof, "")
        assertFails { readNativeCheckCleanup(receipt, root) }
    }
    @Test fun corruptIdentityWithoutCleanupIsStillAnExplicitReadFailure() = fixture { root ->
        val receipt = CheckProcessReceipt("attempt", "fixture", 123)
        val files = NativeCheckReceiptFiles(root, receipt.id)
        files.record(NativeCheckIdentity(receipt, 456, "group"))
        val identity = Files.list(root).use { it.findFirst().orElseThrow() }
        Files.writeString(identity, "{truncated")
        assertFails { readNativeCheckCleanup(receipt, root) }
    }
    @Test fun managedContainmentDoesNotApplyProtectedWorkspaceFileOrNetworkPolicy() {
        val mac = MacResearchSandbox.profile(null)
        assertContains(mac, "SYS_setpgid SYS_setsid SYS_posix_spawn")
        assertFalse(mac.contains("deny file-write")); assertFalse(mac.contains("network-outbound"))
        val linux = LinuxResearchSandbox.arguments(null, Path.of("/workspace"))
        assertContains(linux, "--unshare-pid"); assertContains(linux, "--as-pid-1"); assertContains(linux, "--disable-userns")
        assertTrue(linux.windowed(3).contains(listOf("--bind", "/", "/")))
        assertFalse(linux.contains("--ro-bind")); assertFalse(linux.contains("--unshare-net")); assertFalse(linux.contains("--new-session"))
    }
    @Test fun windowsAuthorityBundleRetainsExactOriginalsAndInheritanceProtection() {
        val original = WindowsCheckAclReceipt("receipt", "S-1-5-21-123", listOf("C:\\fixture"), listOf(
            WindowsCheckAclSnapshot("C:\\fixture", "volume-file-id", "D:PAI(A;OICI;FA;;;SY)"),
            WindowsCheckAclSnapshot("C:\\fixture\\result.txt", "volume-file-id-2", "D:AI(A;ID;FA;;;SY)")))
        assertEquals(original, Json.decodeFromString(WindowsCheckAclReceipt.serializer(), Json.encodeToString(WindowsCheckAclReceipt.serializer(), original)))
    }
}
