package com.nujan.helper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Each vendored drop under `third_party/` is exactly what its `SHA256SUMS` lists: every file matches its hash, and
 * nothing is missing or added. Only the Helper's own files beside a drop (its build file, and for a drop that carries
 * no README of its own, the README) and build output are outside it. A change comes in as a new drop, never as an
 * edit here.
 */
class VendoredDropsChecksumTest {

    @Test
    fun `the ATP drop matches its listed hashes`() = check("atp", own = setOf("wire/build.gradle.kts", "provider/build.gradle.kts"))

    @Test
    fun `the usb-serial-for-android drop matches its listed hashes`() =
        check("usb-serial-for-android", own = setOf("build.gradle.kts", "README.md"))

    private fun check(name: String, own: Set<String>) {
        val drop = File("../third_party/$name").canonicalFile
        assertTrue("no drop at $drop", drop.isDirectory)
        val listed = File(drop, "SHA256SUMS").readLines().filter { it.isNotBlank() }.associate { line ->
            val (hash, path) = line.split("  ", limit = 2)
            path to hash
        }
        assertTrue("the list is not empty", listed.isNotEmpty())
        for ((path, hash) in listed) {
            val file = File(drop, path)
            assertTrue("$path is listed but missing", file.isFile)
            assertEquals("$path differs from the drop", hash, sha256(file.readBytes()))
        }
        val present = drop.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".kotlin" }
            .filter { it.isFile }
            .map { it.relativeTo(drop).invariantSeparatorsPath }
            .filter { it != "SHA256SUMS" && it !in own }
            .toSet()
        assertEquals("files in the drop that SHA256SUMS does not list", emptySet<String>(), present - listed.keys)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
