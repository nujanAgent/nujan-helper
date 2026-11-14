package com.nujan.helper.core.atp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Each Helper tool is granted in one group, and a tool the table does not know is in the strictest. */
class ToolGroupsTest {

    @Test
    fun `the Helper's tools fall into their groups`() {
        listOf("helper_status", "job_list", "job_status", "job_stop", "job_tail", "job_wait", "dev_server_list").forEach { assertNull(it, ToolGroups.of(it)) }
        listOf("fs_read", "fs_write", "fs_move", "workspace_create", "workspace_list", "export_workspace_zip")
            .forEach { assertEquals(it, ToolGroups.FILES, ToolGroups.of(it)) }
        listOf("run_command", "pkg_install_profile", "a_new_tool").forEach { assertEquals(it, ToolGroups.EXEC, ToolGroups.of(it)) }
        listOf("mcp_server_start", "mcp_server_list", "mcp_everything_echo").forEach { assertEquals(it, ToolGroups.MCP, ToolGroups.of(it)) }
        assertEquals(ToolGroups.APK_INSTALL, ToolGroups.of("install_apk"))
        assertEquals("its status is in the same group", ToolGroups.APK_INSTALL, ToolGroups.of("install_apk_status"))
        listOf("usb_list", "usb_serial_open", "usb_survey", "usb_serial_reset", "usb_serial_close")
            .forEach { assertEquals(it, ToolGroups.USB, ToolGroups.of(it)) }
    }

    @Test
    fun `a tool the table does not know is in the strictest group`() {
        assertEquals(ToolGroups.EXEC, ToolGroups.of("something_new"))
    }
}
