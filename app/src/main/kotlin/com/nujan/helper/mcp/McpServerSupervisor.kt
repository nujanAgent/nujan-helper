package com.nujan.helper.mcp

import com.nujan.helper.core.mcp.McpProxy
import com.nujan.helper.core.mcp.McpServerProfile
import com.nujan.helper.core.mcp.McpTool
import com.nujan.helper.core.mcp.RemoteTool
import com.nujan.helper.core.mcp.StdioMcpClient
import com.nujan.helper.core.runtime.PtyLaunch
import com.nujan.helper.core.runtime.RuntimeLayout
import com.nujan.helper.runtime.ProotStdioProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the curated MCP servers inside the rootfs. Launches a registered
 * server as a piped proot child ([ProotStdioProcess]), speaks MCP to it ([StdioMcpClient]), and holds
 * the live connection + its discovered tools. A ServiceLocator singleton so the live set survives the
 * catalog rebuild; [proxiedTools] puts a server's tools in the `tools/list` of the app that started it.
 *
 * Each connected app runs servers of its own: a server belongs to the app whose call started it, which alone sees,
 * uses and stops it, and it stops when that app is disconnected.
 *
 * A blocking handshake can't be cancelled by a coroutine timeout, so [start] runs a watchdog that
 * destroys the child if the `initialize`/`tools/list` handshake overruns — the destroy unblocks the
 * client's reader, which then throws.
 */
class McpServerSupervisor(
    private val layout: RuntimeLayout,
    /** Called with a server's owner after its tools join or leave that owner's [proxiedTools]. */
    private val onToolsChanged: (owner: String?) -> Unit = {},
) {

    class Live(
        val profile: McpServerProfile,
        val process: ProotStdioProcess,
        val client: StdioMcpClient,
        @Volatile var tools: List<RemoteTool>,
        /** The package of the app that started it; null for one the Helper started itself. */
        val owner: String?,
    )

    private data class Key(val owner: String?, val id: String)

    private val servers = ConcurrentHashMap<Key, Live>()

    /** The servers [caller] may see: an app its own, the Helper's own screens every one. */
    fun status(caller: String?): List<Live> = servers.values.filter { caller == null || it.owner == caller }

    /**
     * The live proxies for the tools of the servers [caller] runs, namespaced `mcp_<server>_<tool>` and
     * routed to the child over its stdio client. Rebuilt each call so `tools/list` reflects the
     * currently-running servers; [com.nujan.helper.atp.HelperToolSource] reads it on every request.
     */
    fun proxiedTools(caller: String?): List<McpTool> = status(caller).flatMap { live ->
        live.tools.map { rt ->
            McpProxy.proxyTool(live.profile.id, rt) { name, json ->
                withContext(Dispatchers.IO) { live.client.callTool(name, json) }
            }
        }
    }

    /**
     * Start [profile]'s server for [owner] (cwd = [wsHostPath]), handshake, and return its tools. Restarts the
     * owner's previous instance. Throws on launch/handshake failure (with the child torn down).
     */
    fun start(profile: McpServerProfile, wsHostPath: String, owner: String?, handshakeTimeoutMs: Long = 20_000): List<RemoteTool> {
        stop(profile.id, owner) // replace the owner's previous instance

        val proc = ProotStdioProcess.start(PtyLaunch.mcpServer(layout, profile.launch, wsHostPath))
        drainStderr(proc, profile.id)

        val done = AtomicBoolean(false)
        Thread({
            val deadline = System.currentTimeMillis() + handshakeTimeoutMs
            while (!done.get() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            if (!done.get()) runCatching { proc.close() } // unblocks the handshake reader
        }, "mcp-${profile.id}-watchdog").apply { isDaemon = true; start() }

        val client = StdioMcpClient(proc.stdout, proc.stdin)
        return try {
            client.initialize()
            val tools = client.listTools()
            done.set(true)
            servers[Key(owner, profile.id)] = Live(profile, proc, client, tools, owner)
            onToolsChanged(owner)
            tools
        } catch (t: Throwable) {
            done.set(true)
            runCatching { client.close() }
            runCatching { proc.close() }
            throw t
        }
    }

    /** Stops [owner]'s server [id], if it runs one. */
    fun stop(id: String, owner: String?) {
        servers.remove(Key(owner, id))?.let {
            runCatching { it.client.close() }
            runCatching { it.process.close() }
            onToolsChanged(owner)
        }
    }

    /** Stops every server [owner] started, as when that app is disconnected from the Helper. */
    fun stopOwnedBy(owner: String) = servers.keys.filter { it.owner == owner }.forEach { stop(it.id, it.owner) }

    /** Tear down every hosted server, once no app is connected or at shutdown, so no child is left orphaned. */
    fun stopAll() = servers.keys.toList().forEach { stop(it.id, it.owner) }

    /** Discard child stderr on a daemon thread so a full pipe never blocks the server. */
    private fun drainStderr(proc: ProotStdioProcess, id: String) {
        Thread({
            runCatching {
                val buf = ByteArray(4096)
                while (proc.stderr.read(buf) >= 0) { /* discard */ }
            }
        }, "mcp-$id-stderr").apply { isDaemon = true; start() }
    }
}
