package com.nujan.helper.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSurveyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun File.file(path: String, text: String = "") = File(this, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun `a repository one folder down is found with its kinds, environment and notes`() {
        val ws = tmp.newFolder("ws")
        ws.file("notes.txt")
        ws.file("weather/.git/HEAD")
        ws.file("weather/pyproject.toml")
        ws.file("weather/.venv/pyvenv.cfg")
        ws.file("weather/README.md")
        ws.file("site/index.html")
        ws.file("photos/a.jpg")

        val survey = WorkspaceSurvey.of(ws)

        assertEquals(listOf("notes.txt", "photos/", "site/", "weather/"), survey.entries)
        assertEquals(
            listOf(
                WorkspaceSurvey.Project("site", listOf("web page"), null, emptyList()),
                WorkspaceSurvey.Project("weather", listOf("git", "python"), "weather/.venv", listOf("weather/README.md")),
            ),
            survey.projects,
        )
    }

    @Test
    fun `a project at the root is named by a dot, and a folder only made by a build is not a project`() {
        val ws = tmp.newFolder("ws")
        ws.file("package.json")
        ws.file("AGENTS.md")
        ws.file("node_modules/left-pad/package.json")
        ws.file("venv/bin/python")

        val survey = WorkspaceSurvey.of(ws)

        assertEquals(listOf(WorkspaceSurvey.Project(".", listOf("node"), null, listOf("AGENTS.md"))), survey.projects)
    }

    @Test
    fun `a long folder lists its first entries and counts the rest`() {
        val ws = tmp.newFolder("ws")
        repeat(25) { ws.file("f%02d.txt".format(it)) }

        val survey = WorkspaceSurvey.of(ws)

        assertEquals(WorkspaceSurvey.MAX_ENTRIES, survey.entries.size)
        assertEquals(5, survey.more)
    }

    @Test
    fun `a link that leads out of the workspace is not followed`() {
        val outside = tmp.newFolder("outside").apply { file("secret/.git/HEAD") }
        val ws = tmp.newFolder("ws")
        val link = File(ws, "elsewhere").toPath()
        val linked = runCatching { java.nio.file.Files.createSymbolicLink(link, File(outside, "secret").toPath()) }.isSuccess
        org.junit.Assume.assumeTrue("this file system makes no links", linked)

        val survey = WorkspaceSurvey.of(ws)

        assertEquals(emptyList<String>(), survey.entries)
        assertEquals(emptyList<WorkspaceSurvey.Project>(), survey.projects)
    }
}
