package com.nujan.helper.core.workspace

import java.io.File

/**
 * What a workspace holds, for a session that has not looked yet: its top-level entries, and the projects in it, found by
 * the files that mark them at the root or one folder down, each with its Python environment and the notes beside it.
 * Links that lead out of the workspace are not followed.
 */
object WorkspaceSurvey {

    data class Project(val path: String, val kinds: List<String>, val venv: String?, val notes: List<String>)

    data class Survey(val entries: List<String>, val more: Int, val projects: List<Project>)

    /** A file or folder that marks a project, and the kind it names. */
    private val MARKERS = linkedMapOf(
        ".git" to "git",
        "package.json" to "node",
        "pyproject.toml" to "python",
        "requirements.txt" to "python",
        "setup.py" to "python",
        "CMakeLists.txt" to "cmake",
        "Makefile" to "make",
        "Cargo.toml" to "rust",
        "go.mod" to "go",
        "platformio.ini" to "platformio",
        "build.gradle" to "gradle",
        "build.gradle.kts" to "gradle",
        "index.html" to "web page",
    )

    private val ENVIRONMENTS = listOf(".venv", "venv", "env")
    private val NOTES = listOf("AGENTS.md", "README.md")

    /** Folders that hold what a project installs or builds, never a project of their own. */
    private val NOT_PROJECTS = setOf("node_modules", "__pycache__", "build", "dist", "target", "site-packages") + ENVIRONMENTS

    const val MAX_ENTRIES = 20

    fun of(workspace: File): Survey {
        val root = workspace.canonicalFile
        val children = inside(root, root)
        val projects = listOfNotNull(project(root, root, ".")) +
            children.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in NOT_PROJECTS }
                .sortedBy { it.name }
                .mapNotNull { project(root, it, it.name) }
        val names = children.sortedBy { it.name }.map { if (it.isDirectory) it.name + "/" else it.name }
        return Survey(names.take(MAX_ENTRIES), (names.size - MAX_ENTRIES).coerceAtLeast(0), projects)
    }

    private fun project(root: File, dir: File, path: String): Project? {
        val names = inside(root, dir).associateBy { it.name }
        val kinds = MARKERS.filterKeys { it in names }.values.distinct()
        if (kinds.isEmpty()) return null
        val prefix = if (path == ".") "" else "$path/"
        val venv = ENVIRONMENTS.firstOrNull { name -> names[name]?.let { File(it, "pyvenv.cfg").isFile } == true }
        return Project(path, kinds, venv?.let { prefix + it }, NOTES.filter { it in names }.map { prefix + it })
    }

    /** The entries of [dir] that resolve inside [root]. */
    private fun inside(root: File, dir: File): List<File> =
        dir.listFiles().orEmpty().filter { WorkspaceTree.contains(root, it) }
}
