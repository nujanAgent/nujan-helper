package com.nujan.helper.core.workspace

import com.nujan.helper.core.mcp.ToolCaller
import java.io.File

/**
 * Whose workspaces a call sees. The Helper's own screens and the apps it trusts by publisher (NuJan's builds) share the
 * workspaces under `files/workspaces`; every other connected app has its own under [APPS]/<package>, which no other app
 * can name, because a workspace id never contains a dot.
 */
object WorkspaceScope {
    const val APPS = ".apps"

    fun rootFor(workspacesDir: File, caller: ToolCaller?): File =
        if (caller == null || caller.pinned) workspacesDir else File(workspacesDir, "$APPS/${caller.packageName}")
}
