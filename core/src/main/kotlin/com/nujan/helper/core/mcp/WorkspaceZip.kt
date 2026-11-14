package com.nujan.helper.core.mcp

import com.nujan.helper.core.workspace.WorkspaceTree
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure workspace zipping for `export_workspace_zip` — get build artifacts
 * out of the 0600 sandbox. JVM-only (`java.util.zip`) so it's unit-testable in `:core`;
 * `:app` resolves the workspace dir, calls [zipInto] to write under the FileProvider-mapped `exports/`,
 * then fires the Android share sheet so the owner can save it to Files/Downloads or send it.
 */
object WorkspaceZip {
    /** A stable, filesystem-safe archive name for [workspace] captured at [epochMillis]. */
    fun zipName(workspace: String, epochMillis: Long): String = "$workspace-$epochMillis.zip"

    /**
     * Recursively zip [srcDir]'s files into [destZip] with srcDir-relative entry names; returns destZip. What a link
     * reaches outside [srcDir] is left out, and a linked folder is not followed ([WorkspaceTree.files]).
     */
    fun zipInto(srcDir: File, destZip: File): File {
        destZip.parentFile?.mkdirs()
        ZipOutputStream(destZip.outputStream().buffered()).use { zos ->
            WorkspaceTree.files(srcDir, srcDir).filter { it.absolutePath != destZip.absolutePath }.forEach { file ->
                zos.putNextEntry(ZipEntry(file.relativeTo(srcDir).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return destZip
    }
}
