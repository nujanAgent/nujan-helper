package com.nujan.helper.core.mcp

/**
 * A curated, installable **MCP server** the Helper can host inside its Linux rootfs and proxy to
 * NuJan, so NuJan gains new tools dynamically. Mirrors
 * [InstallProfile]: [installScript] is an idempotent shell recipe run as a job that must print
 * [okMarker]; [launch] is the argv the supervisor starts for a [transport] MCP session
 * (cwd = a workspace). Kept in `:core` so the recipe + launch are unit-testable without a device.
 *
 * Only curated servers are offered (like [InstallProfile]) — arbitrary MCP servers are not installed
 * from model input. `mcp` capability gates the hosting tools; child-server tools are proxied ASK.
 */
enum class McpTransport { STDIO }

data class McpServerProfile(
    val id: String,
    val summary: String,
    /** Human description of the tools this server adds (for `mcp_server_list`). */
    val provides: String,
    val installScript: String,
    /** argv to start the server speaking [transport] (cwd = a workspace). */
    val launch: List<String>,
    /** About how much of the phone's storage the install takes while it runs, npm's downloads included. */
    val needsBytes: Long,
    val transport: McpTransport = McpTransport.STDIO,
    /** Its name on the Helper's own screens. */
    val title: String = id,
    /** What it is, for the person, on the Helper's own screens; [summary] and [provides] are written for the model. */
    val about: String = provides,
) {
    /** Success marker the [installScript] prints (mirrors [InstallProfile.okMarker]). */
    val okMarker: String = "MCP_SERVER_${id.uppercase().replace('-', '_')}_OK"

    /** What its install is called on the Helper's screens, which list hosted servers as plugins. */
    val installLabel: String get() = "Installing the $title plugin"
}

object McpServerProfiles {
    /**
     * The MCP reference "everything" server — exercises tools + resources + prompts, so it's the ideal
     * probe for proving the hub end to end. npm-installed (needs the `web` profile's Node); version
     * pinned to a known-published release (verified on npm: 2026.7.4). Launches over stdio.
     */
    val EVERYTHING = McpServerProfile(
        id = "everything",
        summary = "MCP reference 'everything' server (tools + resources + prompts) — proves the hub.",
        provides = "reference MCP tools: echo, add, longRunningOperation, printEnv, sampleLLM, …",
        installScript = """
            set -e
            npm install -g @modelcontextprotocol/server-everything@2026.7.4
            command -v mcp-server-everything >/dev/null
            echo MCP_SERVER_EVERYTHING_OK
        """.trimIndent(),
        launch = listOf("mcp-server-everything", "stdio"),
        // The package with its dependencies, and npm's cache of what it downloads, with room to spare.
        needsBytes = 100L * MIB,
        title = "Everything",
        about = "The MCP project's reference server: sample tools, prompts and resources that show servers run here.",
    )

    private const val MIB = 1024L * 1024

    private val all: List<McpServerProfile> = listOf(EVERYTHING)

    val ids: List<String> = all.map { it.id }

    fun get(id: String): McpServerProfile? = all.firstOrNull { it.id == id }
}
