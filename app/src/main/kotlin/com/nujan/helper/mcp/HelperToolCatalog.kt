package com.nujan.helper.mcp

import com.nujan.helper.core.mcp.Args
import com.nujan.helper.core.mcp.Args.boolOr
import com.nujan.helper.core.mcp.Args.hints
import com.nujan.helper.core.mcp.Args.longOr
import com.nujan.helper.core.mcp.Args.objectSchema
import com.nujan.helper.core.mcp.Args.str
import com.nujan.helper.core.mcp.Args.strOr
import com.nujan.helper.core.mcp.ApkInstall
import com.nujan.helper.core.mcp.Capability
import com.nujan.helper.core.mcp.DevServers
import com.nujan.helper.core.mcp.InstallProfiles
import com.nujan.helper.core.mcp.McpJson
import com.nujan.helper.core.mcp.McpServerProfiles
import com.nujan.helper.core.mcp.McpTool
import com.nujan.helper.core.mcp.SimpleTool
import com.nujan.helper.core.mcp.ToolResult
import com.nujan.helper.core.mcp.WorkspaceZip
import com.nujan.helper.core.mcp.callingApp
import com.nujan.helper.core.runtime.InputPrompt
import com.nujan.helper.core.runtime.Job
import com.nujan.helper.core.runtime.JobEngine
import com.nujan.helper.core.runtime.JobSpec
import com.nujan.helper.core.runtime.JobState
import com.nujan.helper.core.runtime.InstallRoom
import com.nujan.helper.core.runtime.RuntimeLayout
import com.nujan.helper.core.serial.SerialPortLookup
import com.nujan.helper.core.workspace.WorkspaceSurvey
import com.nujan.helper.core.workspace.WorkspaceTree
import com.nujan_agent.atp.provider.AtpUiOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The MCP tool catalog: workspace + filesystem + command/job + dev-server tools, every
 * one workspace-confined through [WorkspaceFs] (which layers the pure [WorkspacePaths] lexical check
 * with a physical symlink guard) and returning failures as `isError` results, never throwing.
 * Assembled here so each handler closes over the real managers: `pkg_install_profile` installs the
 * curated toolchains, and `install_apk` puts an APK built in a workspace on the phone.
 *
 * [installer] hands a resolved APK to Android's package installer and reports Android's answer.
 * [exportsDir] is the FileProvider-mapped app dir workspace zips are made in; [shareFile] opens the
 * Android share sheet for a staged file and says whether it showed (`export_workspace_zip`). [supervisor] hosts curated
 * MCP servers in the rootfs and holds their live connections (`mcp_server_*`). [usbNote] says how a
 * toolbox program reaches a USB board; `run_command` adds it to a command that looks for a serial port
 * where the toolbox has none ([SerialPortLookup]).
 */
class HelperToolCatalog(
    private val layout: RuntimeLayout,
    private val jobEngine: JobEngine,
    private val installer: com.nujan.helper.install.ApkInstaller,
    private val exportsDir: () -> File,
    private val shareFile: suspend (file: File) -> AtpUiOutcome,
    private val supervisor: McpServerSupervisor,
    private val usbNote: () -> String,
    /** The phone's free storage, which an install must leave room in ([InstallRoom]). */
    private val freeBytes: () -> Long,
) {
    private val fs = WorkspaceFs(layout)
    private val readCap = 256 * 1024

    fun tools(): List<McpTool> = listOf(
        workspaceCreate(), workspaceList(),
        fsList(), fsRead(), fsStat(), fsWrite(), fsDelete(), fsMove(),
    ) + WorkspaceTextTools(fs, readCap).tools() + listOf(
        runCommand(), jobStatus(), jobWait(), jobTail(), jobList(), jobInput(), jobStop(),
        devServerList(), pkgInstallProfile(),
        installApkTool(), installApkStatusTool(), exportWorkspaceZip(),
        mcpServerList(), mcpServerInstall(),
        mcpServerStart(), mcpServerStop(), mcpServerStatus(),
    )

    private fun json(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String = McpJson.objectText(build)

    // ---- workspace ----

    private fun workspaceCreate() = SimpleTool(
        "workspace_create", "Create (or reuse) a project workspace; returns its id.",
        objectSchema("name" to "Human name; sanitized to a safe id.", required = listOf("name")),
        annotations = hints(readOnly = false, destructive = false, idempotent = true),
    ) { args ->
        val name = args.str("name") ?: return@SimpleTool ToolResult.error("missing 'name'")
        runCatching { fs.createWorkspace(name) }.fold(
            { id ->
                val path = fs.workspaceDir(id).absolutePath
                ToolResult.ok(json { put("id", id); put("path", path) })
            },
            { ToolResult.error(it.message ?: "create failed") },
        )
    }

    private fun workspaceList() = SimpleTool(
        "workspace_list",
        "List the workspaces and what each holds: its top-level entries, and the projects in it (a git repository, " +
            "a Node, Python, CMake or other project, at the root or one folder down) with their Python environment " +
            "and their AGENTS.md or README.md.",
        objectSchema(),
        annotations = hints(readOnly = true),
    ) {
        val ids = fs.listWorkspaces()
        val surveys = withContext(Dispatchers.IO) { ids.associateWith { WorkspaceSurvey.of(fs.workspaceDir(it)) } }
        ToolResult.ok(json {
            put("workspaces", buildJsonArray {
                ids.forEach { id ->
                    val survey = surveys.getValue(id)
                    add(buildJsonObject {
                        put("id", id)
                        put("entries", buildJsonArray { survey.entries.forEach { add(it) } })
                        if (survey.more > 0) put("moreEntries", survey.more)
                        if (survey.projects.isNotEmpty()) put("projects", buildJsonArray {
                            survey.projects.forEach { p ->
                                add(buildJsonObject {
                                    put("path", p.path)
                                    put("kinds", buildJsonArray { p.kinds.forEach { add(it) } })
                                    p.venv?.let { put("venv", it) }
                                    if (p.notes.isNotEmpty()) put("notes", buildJsonArray { p.notes.forEach { add(it) } })
                                })
                            }
                        })
                    })
                }
            })
        })
    }

    // ---- filesystem (workspace-confined) ----

    private fun fsList() = SimpleTool(
        "fs_list", "List a directory inside a workspace.",
        objectSchema("workspace" to "Workspace id.", "path" to "Relative path (default root).", required = listOf("workspace")),
        annotations = hints(readOnly = true),
    ) { args ->
        confined(args) { dir ->
            if (!dir.exists()) return@confined ToolResult.error("no such path")
            val entries = if (dir.isDirectory) dir.listFiles().orEmpty() else arrayOf(dir)
            ToolResult.ok(json {
                put("entries", buildJsonArray {
                    entries.sortedBy { it.name }.forEach { f ->
                        add(buildJsonObject {
                            put("name", f.name)
                            put("type", if (f.isDirectory) "dir" else "file")
                            put("size", f.length())
                        })
                    }
                })
            })
        }
    }

    private fun fsRead() = SimpleTool(
        "fs_read",
        "Read a text file inside a workspace: the whole file up to ${readCap / 1024} KB, or the lines from_line to to_line " +
            "of a file of any size, each after its number and a tab, which are not part of the file.",
        com.nujan.helper.core.mcp.Args.schema(
            com.nujan.helper.core.mcp.Args.string("workspace", "Workspace id."),
            com.nujan.helper.core.mcp.Args.string("path", "Relative file path."),
            com.nujan.helper.core.mcp.Args.integer("from_line", "The first line to read, from 1."),
            com.nujan.helper.core.mcp.Args.integer("to_line", "The last line to read; $READ_LINES lines from from_line when left out."),
            required = listOf("workspace", "path"),
        ),
        annotations = hints(readOnly = true),
    ) { args ->
        confined(args) { f ->
            if (!f.isFile) return@confined ToolResult.error("not a file")
            val from = args.longOr("from_line", 0L)
            val to = args.longOr("to_line", 0L)
            if (from <= 0L && to <= 0L) {
                if (f.length() > readCap) {
                    return@confined ToolResult.error("file too large (${f.length()} > $readCap bytes); read it in parts with from_line and to_line")
                }
                return@confined ToolResult.ok(f.readText())
            }
            val first = from.coerceAtLeast(1L)
            val last = if (to >= first) to else first + READ_LINES - 1
            val lines = f.useLines { all -> all.drop((first - 1).toInt()).take((last - first + 1).toInt()).toList() }
            ToolResult.ok(json {
                put("from_line", first)
                put("to_line", first + lines.size - 1)
                put("text", lines.mapIndexed { i, line -> "${first + i}\t$line" }.joinToString("\n").take(readCap))
            })
        }
    }

    private fun fsStat() = SimpleTool(
        "fs_stat", "Stat a path inside a workspace.",
        objectSchema("workspace" to "Workspace id.", "path" to "Relative path.", required = listOf("workspace", "path")),
        annotations = hints(readOnly = true),
    ) { args ->
        confined(args) { f ->
            if (!f.exists()) return@confined ToolResult.error("no such path")
            ToolResult.ok(json {
                put("type", if (f.isDirectory) "dir" else "file")
                put("size", f.length())
                put("mtime", f.lastModified())
            })
        }
    }

    private fun fsWrite() = SimpleTool(
        "fs_write", "Write a text file inside a workspace (creates parents).",
        objectSchema("workspace" to "Workspace id.", "path" to "Relative file path.", "content" to "File content.", required = listOf("workspace", "path", "content")),
        // It replaces a file that is there; the same content written again changes nothing more.
        annotations = hints(readOnly = false, destructive = true, idempotent = true),
    ) { args ->
        val content = args.str("content") ?: return@SimpleTool ToolResult.error("missing 'content'")
        confined(args) { f ->
            f.parentFile?.mkdirs()
            f.writeText(content)
            ToolResult.ok(json { put("written", content.toByteArray().size) })
        }
    }

    private fun fsDelete() = SimpleTool(
        "fs_delete", "Delete a file or directory inside a workspace; a link is deleted, never what it points to.",
        objectSchema("workspace" to "Workspace id.", "path" to "Relative path.", required = listOf("workspace", "path")),
        annotations = hints(readOnly = false, destructive = true, idempotent = true),
    ) { args ->
        confined(args) { f ->
            if (!f.exists()) return@confined ToolResult.error("no such path")
            val ok = WorkspaceTree.delete(fs.workspaceDir(args.str("workspace").orEmpty()), f)
            if (ok) ToolResult.ok(json { put("deleted", true) }) else ToolResult.error("delete failed")
        }
    }

    private fun fsMove() = SimpleTool(
        "fs_move", "Move/rename within a workspace.",
        objectSchema("workspace" to "Workspace id.", "from" to "Source relative path.", "to" to "Destination relative path.", required = listOf("workspace", "from", "to")),
        annotations = hints(readOnly = false, destructive = true, idempotent = false),
    ) { args ->
        val ws = args.str("workspace") ?: return@SimpleTool ToolResult.error("missing 'workspace'")
        val from = args.str("from") ?: return@SimpleTool ToolResult.error("missing 'from'")
        val to = args.str("to") ?: return@SimpleTool ToolResult.error("missing 'to'")
        runCatching {
            val src = fs.resolve(ws, from)
            val dst = fs.resolve(ws, to)
            if (!src.exists()) return@SimpleTool ToolResult.error("source not found")
            dst.parentFile?.mkdirs()
            if (src.renameTo(dst)) ToolResult.ok(json { put("moved", true) }) else ToolResult.error("move failed")
        }.getOrElse { ToolResult.error(it.message ?: "denied") }
    }

    // ---- command / jobs ----

    /** Submits [spec] as a job of the app whose call is running, which alone then sees and stops it. */
    private suspend fun submit(spec: JobSpec): Job = jobEngine.submit(spec.copy(owner = callingApp()))

    /** The job [id] when the calling app may see it; another app's job is as unknown as one that never ran. */
    private suspend fun ownJob(id: String): Job? = jobEngine.status(id)?.takeIf { it.visibleTo(callingApp()) }

    private fun runCommand() = SimpleTool(
        "run_command",
        "Run a shell command in a workspace inside the Linux rootfs. Returns output if it finishes within ~20s, else a " +
            "jobId to wait on with job_wait; 'waitingAt' means it stopped at a question, which job_input answers. " +
            "A command is stopped after ${COMMAND_TIMEOUT_MS / 60_000} minutes, and what it leaves running in the " +
            "background stops when it ends: start a server or a watcher in the foreground with server true. USB " +
            "boards are not under /dev here: usb_serial_open shares one as a port URL.",
        Args.schema(
            Args.string("workspace", "Workspace id."),
            Args.string("command", "Shell command line."),
            Args.boolean(
                "server",
                "True for a command that runs until it is stopped, such as a dev server or a file watcher: no time " +
                    "limit, it does not hold up other commands, and job_stop ends it. False when left out.",
            ),
            required = listOf("workspace", "command"),
        ),
        networkAccess = true, // a command may fetch from the network (its output is marked untrusted)
        requiredCapability = com.nujan.helper.core.mcp.Capability.SHELL, // backstop: off ⇒ denied
        annotations = hints(readOnly = false, destructive = true, openWorld = true),
    ) { args ->
        val ws = args.str("workspace") ?: return@SimpleTool ToolResult.error("missing 'workspace'")
        val command = args.str("command") ?: return@SimpleTool ToolResult.error("missing 'command'")
        if (!com.nujan.helper.core.workspace.WorkspaceId.isValid(ws)) return@SimpleTool ToolResult.error("invalid workspace id")
        val server = args.boolOr("server", false)
        val wsDir = fs.workspaceDir(ws).apply { mkdirs() }
        val job = submit(
            JobSpec(
                command = listOf("sh", "-c", command),
                workspaceId = ws,
                workspaceHostPath = wsDir.absolutePath,
                timeoutMs = if (server) 0 else COMMAND_TIMEOUT_MS,
                server = server,
            ),
        )
        val question = awaitOrQuestion(job.id, RUN_INLINE_MS)
        val now = jobEngine.status(job.id) ?: job
        if (now.state.terminal) {
            val output = jobEngine.tail(job.id) ?: ""
            ToolResult.ok(json {
                put("jobId", job.id); put("state", now.state.name); put("exitCode", now.exitCode ?: -1)
                put("output", output)
                if (SerialPortLookup.isLooking(command, output)) put("usb", usbNote())
            })
        } else {
            ToolResult.ok(json {
                put("jobId", job.id); put("state", now.state.name)
                if (server) put("server", true)
                when {
                    now.state == JobState.QUEUED -> put("hint", queuedHint(now))
                    question != null -> {
                        put("output", jobEngine.tail(job.id, WAIT_TAIL_BYTES) ?: "")
                        put("waitingAt", question); put("hint", QUESTION_HINT)
                    }
                    server -> {
                        put("output", jobEngine.tail(job.id, WAIT_TAIL_BYTES) ?: "")
                        put("hint", SERVER_HINT)
                    }
                    else -> put("hint", WAIT_HINT)
                }
                if (SerialPortLookup.isLooking(command)) put("usb", usbNote())
            })
        }
    }

    private fun jobStatus() = SimpleTool(
        "job_status", "Status of a job by id.",
        objectSchema("jobId" to "Job id.", required = listOf("jobId")),
        annotations = hints(readOnly = true),
    ) { args ->
        val id = args.str("jobId") ?: return@SimpleTool ToolResult.error("missing 'jobId'")
        val job = ownJob(id) ?: return@SimpleTool ToolResult.error("unknown job")
        ToolResult.ok(json {
            put("jobId", job.id); put("state", job.state.name); put("exitCode", job.exitCode ?: -1)
            if (job.server) put("server", true)
        })
    }

    private fun jobWait() = SimpleTool(
        "job_wait",
        "Wait for a job to end, up to 'seconds' (default 60, at most $MAX_WAIT_S), then return its state, exit code and " +
            "the end of its output. While the state is QUEUED or RUNNING, call it again; this is how to wait, not sleep. " +
            "It returns early with 'waitingAt' when the job stops at a question; job_input types the answer. A server " +
            "never ends by itself: for one, it returns once the server is up.",
        Args.schema(
            Args.string("jobId", "Job id."), Args.integer("seconds", "Longest wait in seconds (default 60, at most $MAX_WAIT_S)."),
            required = listOf("jobId"),
        ),
        annotations = hints(readOnly = true),
    ) { args ->
        val id = args.str("jobId") ?: return@SimpleTool ToolResult.error("missing 'jobId'")
        val job = ownJob(id) ?: return@SimpleTool ToolResult.error("unknown job")
        val waitMs = args.longOr("seconds", 60).coerceIn(1, MAX_WAIT_S) * 1_000
        val question = awaitOrQuestion(job.id, waitMs)
        val now = ownJob(id) ?: job
        ToolResult.ok(json {
            put("jobId", now.id); put("state", now.state.name); put("exitCode", now.exitCode ?: -1)
            if (now.server) put("server", true)
            put("output", jobEngine.tail(now.id, WAIT_TAIL_BYTES) ?: "")
            when {
                now.state == JobState.QUEUED -> put("hint", queuedHint(now))
                now.state != JobState.RUNNING -> Unit
                question != null -> { put("waitingAt", question); put("hint", QUESTION_HINT) }
                now.server -> put("hint", SERVER_HINT)
                else -> put("hint", WAIT_HINT)
            }
        })
    }

    /** Why [job] waits for a slot, and what frees one. */
    private fun queuedHint(job: Job): String = if (job.server) {
        "It waits for a free slot: ${jobEngine.maxServers} servers run at a time, and it starts when one is stopped. " +
            "job_list shows this app's jobs, and job_stop ends a server it does not need; job_wait returns once it is up."
    } else {
        "It waits for a free slot: ${jobEngine.maxConcurrent} commands run at a time, and it starts when one ends. " +
            "Wait for it with job_wait; job_list shows this app's jobs."
    }

    /**
     * Waits up to [waitMs] for job [id] to end. Returns the line it waits at when its output stops at a question
     * ([InputPrompt]); null when it ended or the time ran out first. A server never ends by itself, so for one it also
     * returns null once it has printed and gone quiet, as a server does once it is up.
     */
    private suspend fun awaitOrQuestion(id: String, waitMs: Long): String? {
        val server = jobEngine.status(id)?.server == true
        val deadline = android.os.SystemClock.elapsedRealtime() + waitMs
        var seen: String? = null
        var quietSince = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val left = deadline - android.os.SystemClock.elapsedRealtime()
            if (left <= 0) return null
            if (withTimeoutOrNull(minOf(left, QUESTION_POLL_MS)) { jobEngine.await(id) } != null) return null
            val tail = jobEngine.tail(id, InputPrompt.TAIL_BYTES) ?: return null
            val now = android.os.SystemClock.elapsedRealtime()
            if (tail != seen) {
                seen = tail
                quietSince = now
            } else if (now - quietSince >= InputPrompt.QUIET_MS) {
                InputPrompt.waitingAt(tail)?.let { return it }
                if (server && tail.isNotBlank()) return null
            }
        }
    }

    private fun jobTail() = SimpleTool(
        "job_tail", "Tail of a job's output (trimmed).",
        Args.schema(Args.string("jobId", "Job id."), Args.integer("maxBytes", "Max bytes (default 65536)."), required = listOf("jobId")),
        annotations = hints(readOnly = true),
    ) { args ->
        val id = args.str("jobId") ?: return@SimpleTool ToolResult.error("missing 'jobId'")
        val max = args.longOr("maxBytes", 64 * 1024).toInt().coerceIn(1024, 256 * 1024)
        val job = ownJob(id) ?: return@SimpleTool ToolResult.error("unknown job")
        val tail = jobEngine.tail(job.id, max) ?: return@SimpleTool ToolResult.error("unknown job")
        ToolResult.ok(tail)
    }

    private fun jobList() = SimpleTool(
        "job_list", "List the jobs this app's calls started, with their state; a server is marked as one.", objectSchema(),
        annotations = hints(readOnly = true),
    ) {
        val caller = callingApp()
        ToolResult.ok(json {
            put("jobs", buildJsonArray {
                jobEngine.list().filter { it.visibleTo(caller) }.forEach { j ->
                    add(buildJsonObject {
                        put("jobId", j.id); put("state", j.state.name); put("exitCode", j.exitCode ?: -1)
                        if (j.server) put("server", true)
                    })
                }
            })
        })
    }

    private fun jobInput() = SimpleTool(
        "job_input",
        "Type 'text' into a running job, as a person at its terminal would, such as the answer to the question job_wait " +
            "reported in 'waitingAt'; Enter follows unless 'enter' is false. Then call job_wait to see what it does next.",
        Args.schema(
            Args.string("jobId", "Job id."),
            Args.string("text", "What to type."),
            Args.boolean("enter", "Press Enter after the text (default true)."),
            required = listOf("jobId", "text"),
        ),
        annotations = hints(readOnly = false, destructive = false, idempotent = false),
    ) { args ->
        val id = args.str("jobId") ?: return@SimpleTool ToolResult.error("missing 'jobId'")
        val text = args.str("text") ?: return@SimpleTool ToolResult.error("missing 'text'")
        val job = ownJob(id) ?: return@SimpleTool ToolResult.error("unknown job")
        val typed = text + if (args.boolOr("enter", true)) "\r" else ""
        if (!jobEngine.input(job.id, typed.toByteArray(Charsets.UTF_8))) {
            val state = (ownJob(id) ?: job).state
            val why = if (state == JobState.RUNNING) "its terminal is not open" else "it is ${state.name}, not running"
            return@SimpleTool ToolResult.error("job $id takes no input: $why")
        }
        ToolResult.ok(json { put("jobId", job.id); put("typed", text); put("hint", "Call job_wait to see what the job does with it.") })
    }

    private fun jobStop() = SimpleTool(
        "job_stop", "Stop a job or a server (SIGTERM then SIGKILL); a queued job never starts.",
        objectSchema("jobId" to "Job id.", required = listOf("jobId")),
        annotations = hints(readOnly = false, destructive = true, idempotent = true),
    ) { args ->
        val id = args.str("jobId") ?: return@SimpleTool ToolResult.error("missing 'jobId'")
        val job = ownJob(id) ?: return@SimpleTool ToolResult.error("unknown job")
        jobEngine.cancel(job.id)
        ToolResult.ok(json { put("stopping", id) })
    }

    private fun devServerList() = SimpleTool(
        "dev_server_list",
        "List the dev servers this app's jobs started, each with its preview URL (http://127.0.0.1:<port>). A server " +
            "is listed once it has printed its port and answers on it.",
        objectSchema(),
        annotations = hints(readOnly = true),
    ) {
        val caller = callingApp()
        val running = jobEngine.list()
            .filter { it.state == JobState.RUNNING && it.visibleTo(caller) }
            .map { it.id to (jobEngine.tail(it.id, 64 * 1024) ?: "") }
        // A port no job of the caller's announced could be another app's server, so an app hears only its own.
        val servers = DevServers.discover(running, android.os.Process.myUid()).filter { caller == null || it.jobId != null }
        ToolResult.ok(json {
            put("servers", buildJsonArray {
                servers.forEach { s ->
                    add(buildJsonObject {
                        put("port", s.port)
                        put("url", "http://127.0.0.1:${s.port}")
                        s.jobId?.let { put("jobId", it) }
                    })
                }
            })
            // Back-compat flat port list for existing consumers.
            put("ports", buildJsonArray { servers.map { it.port }.distinct().sorted().forEach { add(it) } })
        })
    }

    // ---- package profiles ----

    private fun pkgInstallProfile() = SimpleTool(
        "pkg_install_profile",
        "Provision a named toolchain profile into the Linux rootfs (e.g. \"web\" = Node.js LTS + git + " +
            "build tools). Long-running: returns a jobId to poll (success = job output contains the " +
            "profile's okMarker). Only curated profiles are offered; arbitrary installs go via run_command.",
        objectSchema(
            "profile" to "Profile id to install. Known: ${InstallProfiles.ids.joinToString(", ")}.",
            required = listOf("profile"),
        ),
        networkAccess = true, // fetches packages ⇒ needs NETWORK too (CapabilityGate checks it separately)
        requiredCapability = Capability.PKG, // backstop: off ⇒ denied
        // Every recipe adds and skips what is already there.
        annotations = hints(readOnly = false, destructive = false, idempotent = true, openWorld = true),
    ) { args ->
        val id = args.str("profile") ?: return@SimpleTool ToolResult.error("missing 'profile'")
        val profile = InstallProfiles.get(id)
            ?: return@SimpleTool ToolResult.error(
                "unknown profile '$id'; known: ${InstallProfiles.ids.joinToString(", ")}",
            )
        // A profile whose programs are all there runs again as a check, which can still refresh apt's lists or bring a
        // pinned program up to its version; that little comes out of the reserve, so the reserve is all it needs free.
        val installed = profile.isInstalledIn(layout.rootfsDir)
        val run = profile.runLabel(installed)
        InstallRoom.refusal(run, if (installed) 0L else profile.needsBytes, freeBytes())
            ?.let { return@SimpleTool ToolResult.error(it) }
        // apt is rootfs-wide; a dedicated 'profiles' workspace just gives the job a cwd + a home for logs.
        val wsId = fs.createWorkspace("profiles")
        val wsDir = fs.workspaceDir(wsId).apply { mkdirs() }
        val job = submit(
            JobSpec(
                command = listOf("sh", "-c", profile.script),
                workspaceId = wsId,
                workspaceHostPath = wsDir.absolutePath,
                timeoutMs = INSTALL_TIMEOUT_MS,
                label = run,
            ),
        )
        ToolResult.ok(json {
            put("jobId", job.id)
            put("profile", profile.id)
            put("state", job.state.name)
            put("provides", buildJsonArray { profile.provides.forEach { add(it) } })
            put("okMarker", profile.okMarker)
            put("hint", "${startedHint(job)} Success: the output contains '${profile.okMarker}'.")
        })
    }

    /** What a call that leaves [job] to run says to do next: wait for it, or why it waits for a slot first. */
    private fun startedHint(job: Job): String = if (job.state == JobState.QUEUED) queuedHint(job) else WAIT_HINT

    // ---- device loop: install the Helper's own build output ----

    private fun installApkTool() = SimpleTool(
        "install_apk",
        "Install an APK built in a workspace on this phone. Android asks the person to confirm every install (the " +
            "Helper never installs silently): a notification 'Install <app>?' they tap where notifications are on, " +
            "the dialog itself while the Helper is on screen, or the Helper's screen, where waiting installs are " +
            "listed. Waits up to 'wait' seconds for Android's answer and reports it: installed, failed " +
            "with the reason, or still waiting for the person, with a session install_apk_status reports on later. " +
            "'path' is workspace-relative, e.g. build/app.apk.",
        Args.schema(
            Args.string("workspace", "Workspace id."), Args.string("path", "Workspace-relative path to a .apk file."),
            Args.integer("wait", "Seconds to wait for Android's answer, 0 to 120 (default 90)."),
            required = listOf("workspace", "path"),
        ),
        requiredCapability = Capability.APK_INSTALL, // backstop: off ⇒ denied
        // An install replaces an app already there under the same package.
        annotations = hints(readOnly = false, destructive = true, idempotent = false),
    ) { args ->
        confined(args) { apk ->
            if (!apk.isFile) return@confined ToolResult.error("no such file")
            if (!ApkInstall.isApkName(apk.name)) return@confined ToolResult.error("not an .apk file: ${apk.name}")
            val waitMs = args.longOr("wait", DEFAULT_INSTALL_WAIT_S).coerceIn(0, MAX_INSTALL_WAIT_S) * 1_000
            installOutcome(installer.install(apk, waitMs))
        }
    }

    private fun installApkStatusTool() = SimpleTool(
        "install_apk_status",
        "What became of an install install_apk left waiting for the person: installed, failed with the reason, or " +
            "still waiting for them to answer Android's confirmation.",
        Args.schema(Args.integer("session", "The session install_apk returned."), required = listOf("session")),
        requiredCapability = Capability.APK_INSTALL,
        annotations = hints(readOnly = true),
    ) { args ->
        val session = args.longOr("session", -1).toInt()
        installer.status(session)?.let { installOutcome(it, asked = true) }
            ?: ToolResult.error("No install session $session is known to this Helper; install_apk starts one.")
    }

    /**
     * An install's outcome as a tool result. A failed install is an error from install_apk, which tried it, and an
     * answer from install_apk_status ([asked]), which only asked how it went.
     */
    private fun installOutcome(outcome: ApkInstall.Outcome, asked: Boolean = false): ToolResult = when (outcome) {
        is ApkInstall.Outcome.Installed -> ToolResult.ok(json {
            put("installed", true)
            outcome.packageName?.let { put("package", it) }
        })
        is ApkInstall.Outcome.Failed ->
            if (!asked) {
                ToolResult.error("Android did not install it: ${outcome.reason}")
            } else {
                ToolResult.ok(json {
                    put("installed", false)
                    put("reason", "Android did not install it: ${outcome.reason}")
                })
            }
        is ApkInstall.Outcome.WaitingForPerson -> ToolResult.ok(json {
            put("installed", false)
            put("waiting_for_person", true)
            put("session", outcome.session)
            put("hint", waitingHint())
        })
    }

    /** Where the person finds the waiting install: the notification only when it can show, and the Helper's screen. */
    private fun waitingHint(): String =
        (if (installer.allowedToAsk()) "" else "Android first asks the person to let NuJan Helper install apps. ") +
            if (installer.notificationsShown()) {
                "Android is waiting for the person to confirm the install: the notification 'Install …?' on the " +
                    "phone, or NuJan Helper's screen. Ask them, then call install_apk_status with this session."
            } else {
                "Android is waiting for the person to confirm the install. NuJan Helper's notifications are off on " +
                    "this phone, so no notification shows: ask them to open NuJan Helper, where the install waits " +
                    "under 'waiting for you', then call install_apk_status with this session."
            }

    private fun exportWorkspaceZip() = SimpleTool(
        "export_workspace_zip",
        "Zip a workspace and open Android's share sheet on the phone, through the app the person is looking at, so " +
            "they can save it to Files or send it (gets build artifacts out of the app-private sandbox). Returns the " +
            "archive name and size, or says why the share sheet did not open.",
        objectSchema("workspace" to "Workspace id.", required = listOf("workspace")),
        annotations = hints(readOnly = false, destructive = false, idempotent = false),
    ) { args ->
        val ws = args.str("workspace") ?: return@SimpleTool ToolResult.error("missing 'workspace'")
        if (!com.nujan.helper.core.workspace.WorkspaceId.isValid(ws)) return@SimpleTool ToolResult.error("invalid workspace id")
        val srcDir = fs.workspaceDir(ws)
        if (!srcDir.isDirectory) return@SimpleTool ToolResult.error("no such workspace")
        val dest = File(exportsDir(), WorkspaceZip.zipName(ws, System.currentTimeMillis()))
        runCatching { WorkspaceZip.zipInto(srcDir, dest) }.exceptionOrNull()?.let {
            return@SimpleTool ToolResult.error("export failed: ${it.message}")
        }
        when (shareFile(dest)) {
            AtpUiOutcome.SHOWN -> ToolResult.ok(json {
                put("zip", dest.name); put("bytes", dest.length())
                put("hint", "the share sheet is open on the phone; the person picks where the zip goes")
            })
            AtpUiOutcome.DECLINED, AtpUiOutcome.UNAVAILABLE -> {
                // No app was given the zip, so nothing can be reading it; the next call makes a new one.
                dest.delete()
                ToolResult.error(
                    "The share sheet did not open: Android shows it only while the person is looking at the app that " +
                        "asks. Ask them to keep NuJan open, then call export_workspace_zip again.",
                )
            }
        }
    }

    // ---- MCP hub: install/list curated MCP servers to host in the rootfs ----

    private fun mcpServerList() = SimpleTool(
        "mcp_server_list",
        "List the curated MCP servers the Helper can install and host. Once one is installed and started " +
            "with mcp_server_start, its tools join this app's tool list. Read-only.",
        objectSchema(),
        annotations = hints(readOnly = true),
    ) {
        ToolResult.ok(json {
            put("servers", buildJsonArray {
                McpServerProfiles.ids.forEach { id ->
                    val p = McpServerProfiles.get(id)!!
                    add(buildJsonObject {
                        put("id", p.id); put("summary", p.summary); put("provides", p.provides)
                        put("transport", p.transport.name.lowercase())
                    })
                }
            })
        })
    }

    private fun mcpServerInstall() = SimpleTool(
        "mcp_server_install",
        "Install a curated MCP server into the Linux rootfs so the Helper can host it and offer its tools " +
            "once started. Long-running: returns a jobId to poll (success = output contains " +
            "the okMarker). Needs the 'web' profile (Node). Only curated servers are offered.",
        objectSchema(
            "server" to "Server id to install. Known: ${McpServerProfiles.ids.joinToString(", ")}.",
            required = listOf("server"),
        ),
        networkAccess = true, // npm fetches ⇒ NETWORK checked separately by the gate
        requiredCapability = Capability.MCP, // backstop: off ⇒ denied
        annotations = hints(readOnly = false, destructive = false, idempotent = true, openWorld = true),
    ) { args ->
        val id = args.str("server") ?: return@SimpleTool ToolResult.error("missing 'server'")
        val profile = McpServerProfiles.get(id)
            ?: return@SimpleTool ToolResult.error(
                "unknown server '$id'; known: ${McpServerProfiles.ids.joinToString(", ")}",
            )
        InstallRoom.refusal("Installing the ${profile.id} MCP server", profile.needsBytes, freeBytes())
            ?.let { return@SimpleTool ToolResult.error(it) }
        val wsId = fs.createWorkspace("mcp-servers")
        val wsDir = fs.workspaceDir(wsId).apply { mkdirs() }
        val job = submit(
            JobSpec(
                command = listOf("sh", "-c", profile.installScript),
                workspaceId = wsId,
                workspaceHostPath = wsDir.absolutePath,
                timeoutMs = INSTALL_TIMEOUT_MS,
                label = profile.installLabel,
            ),
        )
        ToolResult.ok(json {
            put("jobId", job.id); put("server", profile.id); put("state", job.state.name)
            put("provides", profile.provides); put("okMarker", profile.okMarker)
            put("hint", "${startedHint(job)} Success: the output contains '${profile.okMarker}'.")
        })
    }

    private fun mcpServerStart() = SimpleTool(
        "mcp_server_start",
        "Start a curated MCP server hosted in the rootfs and return the tools it exposes (install it " +
            "first with mcp_server_install). It runs until mcp_server_stop, and its tools join this " +
            "app's tool list as mcp_<server>_<tool>.",
        objectSchema("server" to "Server id (see mcp_server_list).", required = listOf("server")),
        requiredCapability = Capability.MCP,
        annotations = hints(readOnly = false, destructive = false, idempotent = true),
    ) { args ->
        val id = args.str("server") ?: return@SimpleTool ToolResult.error("missing 'server'")
        val profile = McpServerProfiles.get(id)
            ?: return@SimpleTool ToolResult.error("unknown server '$id'; known: ${McpServerProfiles.ids.joinToString(", ")}")
        val wsId = fs.createWorkspace("mcp-servers")
        val wsDir = fs.workspaceDir(wsId).apply { mkdirs() }
        val owner = callingApp()
        runCatching { withContext(Dispatchers.IO) { supervisor.start(profile, wsDir.absolutePath, owner) } }.fold(
            { tools ->
                ToolResult.ok(json {
                    put("server", id); put("running", true)
                    put("tools", buildJsonArray {
                        tools.forEach { t -> add(buildJsonObject { put("name", t.name); put("description", t.description) }) }
                    })
                    put("hint", "these tools are now in this app's tool list as mcp_${id}_<tool>")
                })
            },
            { ToolResult.error("start failed: ${it.message}") },
        )
    }

    private fun mcpServerStop() = SimpleTool(
        "mcp_server_stop",
        "Stop a hosted MCP server started with mcp_server_start.",
        objectSchema("server" to "Server id.", required = listOf("server")),
        requiredCapability = Capability.MCP,
        annotations = hints(readOnly = false, destructive = true, idempotent = true),
    ) { args ->
        val id = args.str("server") ?: return@SimpleTool ToolResult.error("missing 'server'")
        supervisor.stop(id, callingApp())
        ToolResult.ok(json { put("server", id); put("running", false) })
    }

    private fun mcpServerStatus() = SimpleTool(
        "mcp_server_status",
        "List the MCP servers this app runs in the rootfs and the tools each exposes. Read-only.",
        objectSchema(),
        annotations = hints(readOnly = true),
    ) {
        val caller = callingApp()
        ToolResult.ok(json {
            put("servers", buildJsonArray {
                supervisor.status(caller).forEach { live ->
                    add(buildJsonObject {
                        put("server", live.profile.id)
                        put("running", live.process.isAlive())
                        put("tools", buildJsonArray { live.tools.forEach { t -> add(t.name) } })
                    })
                }
            })
        })
    }

    private suspend inline fun confined(args: JsonObject, block: (File) -> ToolResult): ToolResult = fs.confined(args, block)

    companion object {
        /** How long a command an app runs may take before it is stopped; a server has no limit. */
        const val COMMAND_TIMEOUT_MS = 10 * 60_000L

        /** How long installing packages or an MCP server may take; their downloads outlast most commands. */
        const val INSTALL_TIMEOUT_MS = 20 * 60_000L

        /** How many lines a ranged fs_read gives when the call names no last line. */
        const val READ_LINES = 200
    }
}

/** What a call that leaves a job running says to do next. */
private const val WAIT_HINT = "Wait for it with job_wait; job_tail shows its output so far."
/** What a call that leaves a server running says to do next; it never ends by itself. */
private const val SERVER_HINT = "The server runs until job_stop ends it, so do not wait for it to end. dev_server_list " +
    "gives its preview address; job_tail shows its output."
private const val QUESTION_HINT = "The command is waiting for an answer at 'waitingAt'. Type it with job_input, or stop " +
    "it with job_stop and run it again so that it does not ask: a flag such as -y, --yes or --no-pager."
private const val QUESTION_POLL_MS = 1_000L
/** How long run_command waits for its command to end before it answers with the job to wait on. */
private const val RUN_INLINE_MS = 20_000L
private const val MAX_WAIT_S = 120L
private const val WAIT_TAIL_BYTES = 16 * 1024
private const val DEFAULT_INSTALL_WAIT_S = 90L
private const val MAX_INSTALL_WAIT_S = 120L
