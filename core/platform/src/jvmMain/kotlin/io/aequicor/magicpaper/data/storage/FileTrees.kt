package io.aequicor.magicpaper.data.storage

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView

/**
 * Deletes [root] and everything under it without leaving it through a link: a symbolic link, and on Windows a
 * junction or any other reparse point, is removed as an entry while its target stays. `File.deleteRecursively`
 * and `Path.deleteRecursively` both descend into a junction, and a Git worktree holds them (pnpm's node_modules)
 * pointing at folders the application does not own. A missing [root] is already deleted.
 */
fun deleteTree(root: Path) {
    val attributes = try { Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
    catch (_: NoSuchFileException) { return }
    // Windows reports a junction as a directory that is also "other"; only a plain directory is entered.
    if (attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther)
        Files.newDirectoryStream(root).use { entries -> entries.forEach(::deleteTree) }
    try { Files.deleteIfExists(root) }
    catch (denied: AccessDeniedException) {
        // Windows refuses to delete a read-only file, and Git checks some out read-only.
        val dos = Files.getFileAttributeView(root, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (dos == null || !dos.readAttributes().isReadOnly) throw denied
        dos.setReadOnly(false)
        Files.deleteIfExists(root)
    }
}
