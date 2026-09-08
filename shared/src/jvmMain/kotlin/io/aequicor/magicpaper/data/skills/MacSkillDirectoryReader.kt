package io.aequicor.magicpaper.data.skills

import com.sun.jna.FunctionMapper
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import io.aequicor.magicpaper.domain.SkillPackageFormat
import java.nio.file.Path

/** macOS NIO has no SecureDirectoryStream. Use descriptor-relative openat with O_NOFOLLOW.
 * fstat and readdir use the Darwin INODE64 ABI from sys/stat.h and sys/dirent.h.
 * JNA is already supplied by the desktop OSHI dependency.
 */
internal object MacSkillDirectoryReader {
    private interface LibC : Library {
        fun open(path: String, flags: Int): Int
        fun openat(fd: Int, path: String, flags: Int): Int
        fun read(fd: Int, bytes: ByteArray, size: NativeLong): NativeLong
        fun close(fd: Int): Int
        fun dup(fd: Int): Int
        fun fdopendir(fd: Int): Pointer?
        fun closedir(directory: Pointer): Int
        fun `readdir$INODE64`(directory: Pointer): Pointer?
        fun `fstat$INODE64`(fd: Int, stat: Pointer): Int
    }
    private val libc = Native.load("c", LibC::class.java, mapOf(Library.OPTION_FUNCTION_MAPPER to FunctionMapper { _, method ->
        if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) method.name.removeSuffix("\$INODE64") else method.name
    }))
    private const val FLAGS = 0x00000100 or 0x00000004 or 0x01000000 // NOFOLLOW | NONBLOCK | CLOEXEC
    private const val DIRECTORY = 0x00100000

    fun read(root: Path): List<SkillArchiveEntry> {
        val entries = mutableListOf<SkillArchiveEntry>()
        var visited = 0
        var total = 0L
        fun visit(fd: Int, prefix: String) {
            val duplicate = libc.dup(fd)
            require(duplicate >= 0)
            val directory = libc.fdopendir(duplicate) ?: run { libc.close(duplicate); error("Cannot read skill directory") }
            try {
                while (true) {
                    Native.setLastError(0)
                    val record = libc.`readdir$INODE64`(directory)
                    if (record == null) { require(Native.getLastError() == 0); break }
                    val nameLength = record.getShort(18).toInt() and 0xffff
                    require(nameLength in 1..1023)
                    val name = record.getByteArray(21, nameLength).decodeToString(throwOnInvalidSequence = true)
                    if (name == "." || name == "..") continue
                    require(++visited <= 1024)
                    val relative = prefix + name
                    require(SkillPackageFormat.validPath(relative))
                    val child = libc.openat(fd, name, FLAGS)
                    require(child >= 0) { "Cannot safely open skill entry" }
                    try {
                        val mode = Memory(256).use { stat ->
                            require(libc.`fstat$INODE64`(child, stat) == 0)
                            (stat.getShort(4).toInt() and 0xf000).also { type ->
                                if (type == 0x8000) require((stat.getShort(6).toInt() and 0xffff) == 1) { "Hardlinked skill entry" }
                            }
                        }
                        if (mode == 0x4000) visit(child, "$relative/")
                        else {
                            require(mode == 0x8000 && entries.size < SkillPackageFormat.MAX_FILES + 1) { "Special skill entry" }
                            val limit = if (relative == SkillPackageFormat.MANIFEST) SkillPackageFormat.MAX_MANIFEST_BYTES.toLong() else SkillPackageFormat.MAX_PAYLOAD_BYTES
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = libc.read(child, buffer, NativeLong(buffer.size.toLong())).toLong()
                                require(count >= 0) { "Cannot read skill entry" }
                                if (count == 0L) break
                                total += count
                                require(out.size() + count <= limit && total <= SkillPackageFormat.MAX_PAYLOAD_BYTES + SkillPackageFormat.MAX_MANIFEST_BYTES)
                                out.write(buffer, 0, count.toInt())
                            }
                            entries += SkillArchiveEntry(relative, out.toByteArray())
                        }
                    } finally { libc.close(child) }
                }
            } finally { libc.closedir(directory) }
        }
        val fd = libc.open(root.toAbsolutePath().toString(), FLAGS or DIRECTORY)
        require(fd >= 0) { "Cannot safely open skill directory" }
        try { visit(fd, "") } finally { libc.close(fd) }
        return entries
    }
}
