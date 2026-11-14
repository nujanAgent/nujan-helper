package com.nujan.helper.mcp

import com.nujan.helper.core.mcp.Args.str
import com.nujan.helper.core.mcp.Args.strOr
import com.nujan.helper.core.mcp.ToolResult
import com.nujan.helper.core.mcp.toolCaller
import com.nujan.helper.core.runtime.RuntimeLayout
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import com.nujan.helper.core.workspace.WorkspaceId
import com.nujan.helper.core.workspace.WorkspacePaths
import com.nujan.helper.core.workspace.WorkspaceScope
import com.nujan.helper.core.workspace.WorkspaceTree
import java.io.File

/**
 * Maps a (workspaceId, relative-path) pair onto a real host [File] under the calling app's workspaces
 * ([WorkspaceScope]), enforcing confinement two ways: the pure lexical check ([WorkspacePaths.resolve] — rejects `..`,
 * absolute, NUL, backslash) AND a physical canonical-path check that defeats symlink escapes ([WorkspaceTree.contains]).
 * Every fs tool resolves its path through [resolve], and a tool that walks or deletes a folder does it through
 * [WorkspaceTree], which checks each folder and link it meets the same way.
 */
class WorkspaceFs(private val layout: RuntimeLayout) {

    class Denied(message: String) : SecurityException(message)

    /** The workspaces of the app whose call is running. */
    suspend fun root(): File = WorkspaceScope.rootFor(File(layout.workspacesDir), toolCaller())

    suspend fun workspaceDir(id: String): File {
        if (!WorkspaceId.isValid(id)) throw Denied("invalid workspace id: '$id'")
        return File(root(), id)
    }

    /** List the calling app's workspace ids (directories only). */
    suspend fun listWorkspaces(): List<String> =
        root().listFiles()?.filter { it.isDirectory && WorkspaceId.isValid(it.name) }?.map { it.name }?.sorted().orEmpty()

    /** Create (idempotently) a workspace for [name]; returns its id. */
    suspend fun createWorkspace(name: String): String {
        val id = WorkspaceId.fromName(name)
        File(root(), id).mkdirs()
        return id
    }

    /**
     * Resolve [relative] inside workspace [id] to a real file, confined. Throws [Denied] on any
     * traversal (lexical) or symlink escape (physical). The workspace must exist for reads; for
     * writes the parent is created by the caller.
     */
    suspend fun resolve(id: String, relative: String): File {
        val wsDir = workspaceDir(id)
        val logical = try {
            WorkspacePaths.resolve(wsDir.absolutePath, relative)
        } catch (e: WorkspacePaths.PathViolation) {
            throw Denied(e.message ?: "path violation")
        }
        val target = File(logical)
        // Physical guard: the canonical target must be the workspace dir or below it (symlink-proof).
        if (!WorkspaceTree.contains(wsDir.canonicalFile, target)) {
            throw Denied("path leads out of the workspace, or to nothing, through a link: '$relative'")
        }
        return target
    }

    /**
     * Runs [block] on the file a call's `workspace` and `path` arguments name, resolved by [resolve]; a missing
     * workspace, a path that would leave it, or a failure becomes the call's error.
     */
    suspend inline fun confined(args: JsonObject, block: (File) -> ToolResult): ToolResult {
        val ws = args.str("workspace") ?: return ToolResult.error("missing 'workspace'")
        val path = args.strOr("path", "")
        return try {
            block(resolve(ws, path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Denied) {
            ToolResult.error(e.message ?: "denied")
        } catch (t: Throwable) {
            ToolResult.error(t.message ?: "error")
        }
    }
}
