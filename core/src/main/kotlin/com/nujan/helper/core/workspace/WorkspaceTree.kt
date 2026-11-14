package com.nujan.helper.core.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * A workspace's files as its tools may reach them. A link a job leaves in a workspace can point anywhere the Helper can
 * read or write, other apps' workspaces and the Helper's own files among them, and the tools run in the Helper's own
 * process, outside Linux; so a path a tool acts on must be inside the workspace once its links are resolved. A walk
 * never follows a link to a folder: the folder's files are found under their own path, and such a link can lead back up
 * the tree without end.
 */
object WorkspaceTree {

    /**
     * Whether [file], with every link on its path resolved, is the folder at the real path [realRoot] or inside it. A link
     * to nothing is not: a path that does not exist resolves to where it is written, while writing through the link
     * would create whatever it points at.
     */
    fun contains(realRoot: File, file: File): Boolean {
        val path = file.toPath()
        if (Files.isSymbolicLink(path) && !Files.exists(path)) return false
        val real = file.canonicalPath
        return real == realRoot.path || real.startsWith(realRoot.path + File.separator)
    }

    /**
     * The regular files at and under [start] that are inside [workspace], folder by folder in name order. A folder named
     * in [skip] below [start] is not entered; a link to a file inside the workspace counts as that file; a link to a
     * folder is not followed.
     */
    fun files(workspace: File, start: File, skip: Set<String> = emptySet()): Sequence<File> = sequence {
        val root = workspace.canonicalFile
        when {
            !contains(root, start) -> Unit
            start.isDirectory -> yieldAll(filesIn(root, start, skip))
            start.isFile -> yield(start)
        }
    }

    private fun filesIn(root: File, folder: File, skip: Set<String>): Sequence<File> = sequence {
        // Checked as it is opened, so a folder a job has just replaced with a link is not listed.
        if (!contains(root, folder)) return@sequence
        for (entry in folder.listFiles().orEmpty().sortedBy { it.name }) {
            val path = entry.toPath()
            when {
                Files.isSymbolicLink(path) -> if (Files.isRegularFile(path) && contains(root, entry)) yield(entry)
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> if (entry.name !in skip) yieldAll(filesIn(root, entry, skip))
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> yield(entry)
            }
        }
    }

    /**
     * Deletes [target] and everything under it in [workspace]. A link is deleted itself, never what it points at, and a
     * folder that resolves outside the workspace is not emptied. True when [target] is gone.
     */
    fun delete(workspace: File, target: File): Boolean = deleteUnder(workspace.canonicalFile, target)

    private fun deleteUnder(root: File, file: File): Boolean {
        val path = file.toPath()
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && contains(root, file)) {
            file.listFiles()?.forEach { deleteUnder(root, it) }
        }
        return file.delete() || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    }
}
