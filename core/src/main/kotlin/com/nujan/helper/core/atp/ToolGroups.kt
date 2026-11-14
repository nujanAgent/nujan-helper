package com.nujan.helper.core.atp

/**
 * Which ATP group each Helper tool is granted in (SPEC.md 6.4), by the ids res/xml/atp_provider.xml declares. The status
 * tool and the caller's own jobs and dev servers are in none: every connected app may use them. A tool this table does
 * not know is in [EXEC], the strictest, so a new tool is never more open than it was meant to be.
 */
object ToolGroups {
    const val FILES = "files"
    const val EXEC = "exec"
    const val MCP = "mcp"
    const val APK_INSTALL = "apk-install"
    const val USB = "usb"
    const val PHONE_SEE = "phone-see"
    const val PHONE_ACT = "phone-act"
    const val PHONE_SHELL = "phone-shell"

    private val IMPLICIT = setOf("helper_status", "job_list", "job_status", "job_stop", "job_tail", "job_wait", "dev_server_list")
    private val FILE_TOOLS = setOf("workspace_create", "workspace_list", "export_workspace_zip")
    private val INSTALL_TOOLS = setOf("install_apk", "install_apk_status")

    /** The group [toolName] is in, or null for a tool every connected app may use. */
    fun of(toolName: String): String? = when {
        toolName in IMPLICIT -> null
        toolName in INSTALL_TOOLS -> APK_INSTALL
        toolName.startsWith("mcp_") -> MCP
        toolName.startsWith("usb_") -> USB
        toolName == "phone_shell" -> PHONE_SHELL
        toolName == "phone_act" || toolName == "phone_undo" -> PHONE_ACT
        toolName.startsWith("phone_") -> PHONE_SEE
        toolName.startsWith("fs_") || toolName in FILE_TOOLS -> FILES
        else -> EXEC
    }
}
