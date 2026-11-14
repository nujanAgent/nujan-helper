package com.nujan.helper.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallTest {

    @Test
    fun `accepts apk names regardless of case`() {
        assertTrue(ApkInstall.isApkName("game.apk"))
        assertTrue(ApkInstall.isApkName("Game.APK"))
        assertTrue(ApkInstall.isApkName("build/game.apk".substringAfterLast('/')))
    }

    @Test
    fun `rejects non-apk names and extensionless names`() {
        assertFalse(ApkInstall.isApkName("game.txt"))
        assertFalse("no dot ⇒ no extension", ApkInstall.isApkName("apk"))
        assertFalse(ApkInstall.isApkName("game"))
        assertFalse(ApkInstall.isApkName("game.apk.bak"))
    }

    @Test
    fun `mime is the package-archive type the installer keys on`() {
        assertEquals("application/vnd.android.package-archive", ApkInstall.MIME)
    }

    /** A failed install says why in words a model can act on, and keeps Android's own message. */
    @Test
    fun `an installer status reads as its reason`() {
        assertEquals(
            "it conflicts with an app already installed, most often one signed with a different key",
            ApkInstall.failure(5, null),
        )
        assertEquals("the person declined it, or it was cancelled (INSTALL_FAILED_ABORTED: User rejected)", ApkInstall.failure(3, "INSTALL_FAILED_ABORTED: User rejected"))
        assertEquals("Android did not install it", ApkInstall.failure(99, " "))
    }

    /** A final outcome kept between runs of the Helper reads back as itself; a pending one is never kept. */
    @Test
    fun `a kept outcome reads back as the outcome it was`() {
        listOf(
            ApkInstall.Outcome.Installed("com.example.game"),
            ApkInstall.Outcome.Installed(null),
            ApkInstall.Outcome.Failed("the person declined it, or it was cancelled (INSTALL_FAILED_ABORTED: User rejected)"),
        ).forEach { outcome ->
            assertEquals(outcome, ApkInstall.decode(ApkInstall.encode(outcome)!!))
        }
        assertEquals(null, ApkInstall.encode(ApkInstall.Outcome.WaitingForPerson(7)))
        assertEquals(null, ApkInstall.decode("installed"))
        assertEquals(null, ApkInstall.decode("pending\tcom.example.game"))
    }
}
