package com.nujan.helper.mcp

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nujan.helper.MainActivity
import com.nujan.helper.core.mcp.McpTool
import com.nujan.helper.core.mcp.ToolCaller
import com.nujan.helper.core.mcp.ToolResult
import com.nujan.helper.core.phone.PhoneGuard
import com.nujan.helper.di.ServiceLocator
import com.nujan.helper.phone.PhoneAsks
import com.nujan.helper.phone.PhoneLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone tools against this phone's real shell, as a connected app calls them. Needs a phone the Helper is already paired
 * with and Wireless debugging on, and is skipped otherwise. A call here has no way to ask the person, so what would ask is refused.
 */
@RunWith(AndroidJUnit4::class)
class PhoneToolsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tools: Map<String, McpTool> by lazy { ServiceLocator.helperTools(CALLER).associateBy { it.name } }

    @Before
    fun connected() = runBlocking {
        ServiceLocator.phoneDriving.resume()
        assumeTrue("needs a paired phone", ServiceLocator.phoneLink.status.value != PhoneLink.Status.NOT_PAIRED)
        assumeTrue("needs Wireless debugging on", runCatching { ServiceLocator.phoneLink.connection() }.isSuccess)
        call("phone_act", """{"action":"home","look":false}""")
        delay(800)
    }

    @After
    fun leave() {
        PhoneAsks.answerWithoutAsking = null
        ServiceLocator.phoneDriving.resume()
    }

    private fun call(tool: String, arguments: String = "{}"): ToolResult = runBlocking {
        withContext(ToolCaller(CALLER)) { tools.getValue(tool).call(Json.parseToJsonElement(arguments).jsonObject) }
    }

    @Test
    fun itSaysHowThePhoneIsReached() {
        val status = call("phone_status")
        assertFalse(status.text, status.isError)
        assertTrue(status.text, status.text.contains("\"shell\":\"connected\""))
    }

    @Test
    fun aCommandComesBackWithItsOutputAndStatus() {
        val answer = call("phone_shell", """{"command":"getprop ro.build.version.sdk"}""")
        assertFalse(answer.text, answer.isError)
        assertTrue(answer.text, answer.text.contains(android.os.Build.VERSION.SDK_INT.toString()))
        val failed = call("phone_shell", """{"command":"ls /no-such-folder"}""")
        assertTrue(failed.text, failed.isError)
        assertTrue(failed.text, failed.text.contains("no-such-folder"))
        assertTrue(failed.text, failed.text.contains("\"exit\":"))
    }

    @Test
    fun aCommandThatDoesNotPlainlyOnlyReadIsNotRunWithoutThePerson() {
        listOf("ls; true", "true", "input keyevent KEYCODE_HOME", "settings put system screen_off_timeout 987654").forEach { command ->
            val answer = call("phone_shell", """{"command":"$command"}""")
            assertTrue(answer.text, answer.isError)
            assertTrue(answer.text, answer.text.contains("did not allow"))
        }
    }

    @Test
    fun aStepHardToTakeBackIsNotRunWithoutThePerson() {
        val answer = call("phone_shell", """{"command":"pm uninstall com.example.none"}""")
        assertTrue(answer.text, answer.isError)
        assertTrue(answer.text, answer.text.contains("did not allow"))
    }

    @Test
    fun aSettingChangedCanBePutBack() {
        fun read() = call("phone_shell", """{"command":"settings get system screen_off_timeout"}""").text
        val before = read()
        PhoneAsks.answerWithoutAsking = PhoneAsks.Answer.ONCE
        val changed = call("phone_shell", """{"command":"settings put system screen_off_timeout 987654"}""")
        assertFalse(changed.text, changed.isError)
        assertTrue(read().contains("987654"))
        val undone = call("phone_undo")
        assertFalse(undone.text, undone.isError)
        assertEquals(before, read())
    }

    @Test
    fun theScreenIsReadAndAnAppOpensAndAnswersWithTheNewScreen() {
        val apps = call("phone_apps")
        assertTrue(apps.text, apps.text.contains("  "))
        val home = call("phone_act", """{"action":"home"}""")
        assertFalse(home.text, home.isError)
        assertTrue(home.text, home.text.startsWith("in front:"))
        val tree = call("phone_ui")
        assertFalse(tree.text, tree.isError)
        assertTrue(tree.text, Regex("""(?m)^1 """).containsMatchIn(tree.text))
    }

    @Test
    fun anAppOpenedJustAfterHomeIsInFrontWhenTheCallAnswers() {
        val launchers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).map { it.activityInfo.packageName }
        val app = launchers.distinct().sorted().firstOrNull {
            it != context.packageName && PhoneGuard.appRisk(it) == null && !PhoneGuard.holdsMoneyOrPasswords(it, label = null)
        }
        assumeTrue("needs another app to open", app != null)
        call("phone_act", """{"action":"home","look":false}""")
        val opened = call("phone_act", """{"action":"open_app","package":"$app"}""")
        assertFalse(opened.text, opened.isError)
        assertTrue(opened.text, opened.text.startsWith("in front: $app"))
    }

    @Test
    fun leavingAnAppIsNotAskedAbout() = runBlocking {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(2_000)
        val answer = call("phone_act", """{"action":"home"}""")
        assertFalse(answer.text, answer.isError)
        assertFalse(answer.text, answer.text.startsWith("in front: ${context.packageName}"))
    }

    @Test
    fun anActCanNameAThingByItsNumberInTheList() {
        call("phone_act", """{"action":"home"}""")
        val first = Regex("""(?m)^(\d+) .*\[tap""").find(call("phone_ui").text)
        assertTrue("something on the home screen can be tapped", first != null)
        val tapped = call("phone_act", """{"action":"tap","index":${first!!.groupValues[1]},"look":false}""")
        assertFalse(tapped.text, tapped.isError)
        call("phone_act", """{"action":"home","look":false}""")
    }

    @Test
    fun aNumberNotInTheListIsTurnedBack() {
        call("phone_ui")
        val answer = call("phone_act", """{"action":"tap","index":99999}""")
        assertTrue(answer.text, answer.isError)
        assertTrue(answer.text, answer.text.contains("phone_ui"))
    }

    @Test
    fun theScreenComesBackAsAPicture() {
        val picture = call("phone_screen")
        assertFalse(picture.text, picture.isError)
        assertEquals(1, picture.images.size)
        assertEquals("image/jpeg", picture.images[0].mimeType)
        assertTrue(picture.text, picture.images[0].bytes.size > 1000)
    }

    @Test
    fun settingsAreNotActedOnWithoutThePerson() {
        val answer = call("phone_act", """{"action":"open_app","package":"com.android.settings"}""")
        assertTrue(answer.text, answer.isError)
        assertTrue(answer.text, answer.text.contains("did not allow"))
    }

    @Test
    fun theHelpersOwnScreensCannotBeActedOn() = runBlocking {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(2_000)
        val answer = call("phone_act", """{"action":"tap","x":100,"y":100}""")
        assertTrue(answer.text, answer.isError)
        assertTrue(answer.text, answer.text.contains("own screens"))
        call("phone_act", """{"action":"home","look":false}""")
        Unit
    }

    @Test
    fun turningDrivingOffRefusesEveryToolUntilThePersonTurnsItOn() {
        ServiceLocator.phoneDriving.stop()
        listOf("phone_ui", "phone_screen").forEach { tool ->
            val answer = call(tool)
            assertTrue(answer.text, answer.isError)
            assertTrue(answer.text, answer.text.contains("turned off phone driving"))
        }
        assertEquals(PhoneLink.Status.PAIRED, ServiceLocator.phoneLink.status.value)
        ServiceLocator.phoneDriving.resume()
        assertFalse(call("phone_shell", """{"command":"getprop ro.build.version.sdk"}""").isError)
    }

    private companion object {
        const val CALLER = "com.example.caller"
    }
}
