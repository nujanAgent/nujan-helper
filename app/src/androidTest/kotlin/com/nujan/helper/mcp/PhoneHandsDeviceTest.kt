package com.nujan.helper.mcp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nujan.helper.core.mcp.McpTool
import com.nujan.helper.core.mcp.ToolCaller
import com.nujan.helper.core.mcp.ToolResult
import com.nujan.helper.di.ServiceLocator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A screen to act on, in a process of its own: a field, a password field, and a button that shows its presses. */
class HandsTargetActivity : Activity() {
    private var presses = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        // A keyboard left open takes gestures meant for the screen and types them.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val field = EditText(this).apply { contentDescription = "typing target" }
        val secret = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("hunter2")
        }
        val button = Button(this).apply {
            isAllCaps = false
            text = "Press me"
            setOnClickListener {
                presses++
                text = "Pressed $presses"
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(field)
                addView(secret)
                addView(button)
            },
        )
        field.requestFocus()
    }
}

/**
 * The phone tools through the Hands add-on, judged by what they do on a real screen. Needs the add-on turned on in
 * Accessibility, and is skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class PhoneHandsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tools: Map<String, McpTool> by lazy { ServiceLocator.helperTools(CALLER).associateBy { it.name } }

    @Before
    fun open() {
        ServiceLocator.phoneDriving.resume()
        // Between drives Hands may have turned itself off; a drive turns it back on, and so does each test.
        runBlocking { ServiceLocator.phoneHands.switchOn(ServiceLocator.phoneLink) }
        assumeTrue("needs the Hands add-on on", ServiceLocator.phoneHands.isOn())
        // A new screen for each test, so what one test typed or pressed is not on the next one's.
        val intent = Intent(instrumentation.context, HandsTargetActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runBlocking {
            assertTrue(ServiceLocator.phoneHands.open(intent))
            // Android can hold the start back for some seconds after the last test's Home.
            val deadline = SystemClock.uptimeMillis() + OPEN_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline && ServiceLocator.phoneHands.inFront() != instrumentation.context.packageName) {
                SystemClock.sleep(POLL_MS)
            }
        }
        SystemClock.sleep(SETTLE_MS)
    }

    @After
    fun close() {
        ServiceLocator.phoneDriving.resume()
        if (ServiceLocator.phoneHands.isOn()) call("phone_act", """{"action":"home","look":false}""")
    }

    private fun call(tool: String, arguments: String): ToolResult = runBlocking {
        withContext(ToolCaller(CALLER)) { tools.getValue(tool).call(Json.parseToJsonElement(arguments).jsonObject) }
    }

    /** Polls [done] until it holds or [ms] pass; whether it held. */
    private fun waitUntil(ms: Long = SWITCH_WAIT_MS, done: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < deadline) {
            if (done()) return true
            SystemClock.sleep(POLL_MS)
        }
        return done()
    }

    /**
     * Hands is on only while it is needed: it turns itself off when an app that holds money or passwords comes to the front,
     * and, when the Helper can turn it on again, as soon as a drive ends; the Helper turns it back on for the next drive. Runs
     * only where the Helper has been allowed to turn Hands on and off, so it never leaves someone's Hands off.
     */
    @Test
    fun handsTurnsItselfOffForAMoneyAppAndWhenADriveEndsAndTheHelperTurnsItBackOn() = runBlocking {
        val hands = ServiceLocator.phoneHands
        val link = ServiceLocator.phoneLink
        assumeTrue("needs the Helper allowed to turn Hands on and off", hands.canSwitchItself())
        val target = instrumentation.context.packageName
        try {
            call("phone_act", """{"action":"home","look":false}""")
            hands.configure(listOf(target), offWhenIdle = false)
            val since = System.currentTimeMillis()
            hands.open(Intent(instrumentation.context, HandsTargetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("Hands turned itself off with the guarded app in front", waitUntil { !hands.isOn() })
            assertEquals(target, hands.stoppedFor(since))
            hands.configure(emptyList(), offWhenIdle = false)
            assertTrue("the Helper turned it back on", hands.switchOn(link))

            hands.configure(emptyList(), offWhenIdle = true)
            hands.inUse("Test")
            hands.done()
            assertTrue("Hands turned itself off when the drive ended", waitUntil { !hands.isOn() })
            assertTrue(hands.turnedItselfOff())
            assertTrue("and came back for the next drive", hands.switchOn(link))
        } finally {
            hands.configure(emptyList(), offWhenIdle = false)
            if (!hands.isOn()) hands.switchOn(link)
        }
    }

    /** An app that holds money or passwords is neither opened nor read: the refusal comes before anything is done in it. */
    @Test
    fun anAppThatHoldsMoneyIsNotOpened() {
        val pm = instrumentation.targetContext.packageManager
        val money = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .firstOrNull { (pkg, name) -> com.nujan.helper.core.phone.PhoneGuard.holdsMoneyOrPasswords(pkg, name) }
        assumeTrue("needs an app that holds money or passwords", money != null)
        val opened = call("phone_act", """{"action":"open_app","package":"${money!!.first}"}""")
        assertTrue(opened.text, opened.isError && opened.text.contains("holds money"))
        assertTrue("it was not opened", runBlocking { ServiceLocator.phoneHands.inFront() } != money.first)
    }

    @Test
    fun theTreeNamesWhatIsOnScreenAndHidesThePassword() {
        val list = call("phone_ui", "{}")
        assertFalse(list.text, list.isError)
        assertTrue(list.text, list.text.contains("Press me"))
        assertTrue(list.text, list.text.contains("typing target"))
        assertFalse("a password's text is never given: ${list.text}", list.text.contains("hunter2"))
    }

    @Test
    fun aTapLandsOnTheThingByItsNumber() {
        val list = call("phone_ui", "{}").text
        val line = Regex("""(?m)^(\d+) Button "Press me"""").find(list)
        assertTrue("the button is listed: $list", line != null)
        val tapped = call("phone_act", """{"action":"tap","index":${line!!.groupValues[1]},"look":false}""")
        assertFalse(tapped.text, tapped.isError)
        SystemClock.sleep(SETTLE_MS)
        val after = call("phone_ui", "{}").text
        assertTrue(after, after.contains("Pressed 1"))
    }

    /** A step that leaves the screen as it was says so before the screen, and wait waits as long as asked without acting. */
    @Test
    fun aStepThatChangesNothingSaysSoAndWaitOnlyWaits() {
        call("phone_ui", "{}")
        val scrolled = call("phone_act", """{"action":"scroll","direction":"down"}""")
        assertFalse(scrolled.text, scrolled.isError)
        assertTrue(scrolled.text, scrolled.text.startsWith("Nothing on the screen changed."))

        val started = SystemClock.uptimeMillis()
        val waited = call("phone_act", """{"action":"wait","ms":1200}""")
        assertTrue("waited ${SystemClock.uptimeMillis() - started} ms", SystemClock.uptimeMillis() - started >= 1_200)
        assertFalse(waited.text, waited.isError || waited.text.startsWith("Nothing on the screen changed."))
        assertTrue(waited.text, waited.text.contains("Press me"))

        val button = Regex("""(?m)^(\d+) Button "Press me"""").find(waited.text)!!.groupValues[1]
        val tapped = call("phone_act", """{"action":"tap","index":$button}""")
        assertFalse(tapped.text, tapped.isError || tapped.text.startsWith("Nothing on the screen changed."))
        assertTrue(tapped.text, tapped.text.contains("Pressed 1"))
    }

    /**
     * Steps run in order in one call. A later step that names a number acts only while that thing is where the list put
     * it: the button's words change when it is pressed, so a second press by the same number is not made.
     */
    @Test
    fun stepsRunInOneCallAndStopWhereANumberedThingChanged() {
        val list = call("phone_ui", "{}").text
        val field = Regex("""(?m)^(\d+) .*typing target""").find(list)!!.groupValues[1]
        val button = Regex("""(?m)^(\d+) Button "Press me"""").find(list)!!.groupValues[1]
        val steps = """{"steps":[{"action":"type","index":$field,"text":"Lisbon"},{"action":"tap","index":$button},{"action":"tap","index":$button}]}"""

        val result = call("phone_act", steps)
        assertFalse(result.text, result.isError)
        assertTrue(result.text, result.text.startsWith("Steps done: 2 of 3. Step 3 (tap #$button) was not done"))
        assertTrue(result.text, result.text.contains("Lisbon") && result.text.contains("Pressed 1"))
        assertFalse(result.text, result.text.contains("Pressed 2"))

        val both = call("phone_act", """{"action":"wait","steps":[{"action":"wait"}]}""")
        assertTrue(both.text, both.isError && both.text.contains("not both"))
    }

    /** A scroll with the keyboard open swipes above it, so it types nothing; the screen's reading says the keyboard is open. */
    @Test
    fun aScrollWithTheKeyboardOpenTypesNothing() {
        val field = Regex("""(?m)^(\d+) .*typing target""").find(call("phone_ui", "{}").text)!!.groupValues[1]
        call("phone_act", """{"action":"tap","index":$field,"look":false}""")
        val deadline = SystemClock.uptimeMillis() + OPEN_WAIT_MS
        var screen = call("phone_ui", "{}").text
        while ("keyboard open" !in screen && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MS * 3)
            screen = call("phone_ui", "{}").text
        }
        assumeTrue("needs a keyboard that opens on a tap: $screen", "keyboard open" in screen)

        val scrolled = call("phone_act", """{"action":"scroll","direction":"down"}""")
        assertFalse(scrolled.text, scrolled.isError)
        val fieldLine = Regex("""(?m)^\d+ .*typing target.*$""").find(scrolled.text)!!.value
        assertFalse("the swipe typed on the keyboard: $fieldLine", fieldLine.contains("EditText \""))
    }

    @Test
    fun textInAnyLanguageIsTyped() {
        val words = "नमस्ते Lisbon €5"
        val list = call("phone_ui", "{}").text
        val field = Regex("""(?m)^(\d+) .*typing target""").find(list)
        assertTrue("the field is listed: $list", field != null)
        val arguments = buildJsonObject {
            put("action", "type")
            put("index", field!!.groupValues[1].toInt())
            put("text", JsonPrimitive(words))
            put("look", false)
        }.toString()
        val typed = call("phone_act", arguments)
        assertFalse(typed.text, typed.isError)
        SystemClock.sleep(SETTLE_MS)
        val after = call("phone_ui", "{}").text
        assertTrue(after, after.contains(words))
    }

    /** The screen's size in the tools' coordinates: its real size scaled so the long side is at most 1280. */
    private fun pictureSize(): Pair<Int, Int> {
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        (instrumentation.targetContext.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(size)
        val scale = minOf(1.0, 1280.0 / maxOf(size.x, size.y))
        return Math.round(size.x * scale).toInt() to Math.round(size.y * scale).toInt()
    }

    private fun decoded(result: ToolResult): android.graphics.Bitmap {
        val bytes = result.images.single().bytes
        return requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "not a picture: ${result.text}" }
    }

    /** The picture is in the space phone_act takes: the add-on hands over a picture already made smaller, which is not made smaller again. */
    @Test
    fun theScreenComesBackAsAPictureTheSizeOfTheToolsCoordinates() {
        val picture = call("phone_screen", "{}")
        assertFalse(picture.text, picture.isError)
        val (width, height) = pictureSize()
        val shown = decoded(picture)
        assertTrue("picture ${shown.width}x${shown.height}, coordinates ${width}x$height", kotlin.math.abs(shown.width - width) <= 1 && kotlin.math.abs(shown.height - height) <= 1)
        assertTrue(picture.text, picture.text.contains("\"width\":${shown.width}"))
    }

    @Test
    fun aBurstIsOnePictureOfNumberedFramesWithWhatChangedBetweenThem() {
        val burst = call("phone_screen", """{"burst":3,"every_ms":400}""")
        assertFalse(burst.text, burst.isError)
        val answer = Json.parseToJsonElement(burst.text).jsonObject
        assertTrue(burst.text, answer["burst"].toString() == "3")
        assertTrue(burst.text, answer["summary"].toString().isNotBlank())
        val (width, height) = pictureSize()
        val sheet = decoded(burst)
        assertTrue("sheet ${sheet.width}x${sheet.height}", sheet.width <= maxOf(width, height) && sheet.height <= maxOf(width, height))
        assertTrue("three frames side by side: ${sheet.width}x${sheet.height}", sheet.width > sheet.height)
    }

    @Test
    fun aRegionIsThatPartOfTheScreenShownLarger() {
        val part = call("phone_screen", """{"region":[0,0,200,100]}""")
        assertFalse(part.text, part.isError)
        assertTrue(part.text, part.text.contains("\"region\":[0,0,200,100]"))
        val shown = decoded(part)
        assertTrue("region shown ${shown.width}x${shown.height}", shown.width == 600 && shown.height == 300)
        val outside = call("phone_screen", """{"region":[5000,5000,100,100]}""")
        assertTrue(outside.text, outside.isError)
    }

    @Test
    fun anAdOnAWebPageIsNotTappedWithoutThePersonAndThePagesOwnLinkIs() {
        assumeTrue("needs Chrome", runCatching { instrumentation.targetContext.packageManager.getPackageInfo(CHROME, 0) }.isSuccess)
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { client ->
                    val request = client.getInputStream().bufferedReader()
                    while (request.readLine()?.isNotEmpty() == true) Unit
                    val body = AD_PAGE.toByteArray()
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().write(head.toByteArray() + body)
                }
            }
        }
        try {
            val page = call("phone_act", """{"action":"open_url","url":"http://127.0.0.1:${server.localPort}/download.html"}""")
            assertFalse(page.text, page.isError)
            SystemClock.sleep(PAGE_LOAD_MS)
            fun line(words: String) = Regex("""(?m)^(\d+) .*"$words".* at (\d+),(\d+).*$""").find(call("phone_ui", "{}").text)
            val ad = line("DOWNLOAD NOW")
            assertTrue("the ad is listed", ad != null)
            assertTrue(ad!!.value, ad.value.contains("ad]"))

            // The person declines. A real question needs a host on screen to put it up; this caller has none.
            com.nujan.helper.phone.PhoneAsks.answerWithoutAsking = com.nujan.helper.phone.PhoneAsks.Answer.NO
            val byNumber = call("phone_act", """{"action":"tap","index":${ad.groupValues[1]}}""")
            assertTrue(byNumber.text, byNumber.isError && byNumber.text.contains("did not allow tapping an advertisement"))
            // Aimed from a picture by a caller that never read the list: judged on the screen as it is.
            val (x, y) = ad.groupValues[2] to ad.groupValues[3]
            val byPlace = runBlocking {
                withContext(ToolCaller(UNREAD_CALLER)) {
                    tools.getValue("phone_act").call(Json.parseToJsonElement("""{"action":"tap","x":$x,"y":$y}""").jsonObject)
                }
            }
            assertTrue("a tap aimed by place is judged on the screen: ${byPlace.text}", byPlace.isError && byPlace.text.contains("advertisement"))

            val own = line("Download APK")
            assertTrue("the page's own link is listed", own != null && !own.value.contains("ad]"))
            val tapped = call("phone_act", """{"action":"tap","index":${own!!.groupValues[1]}}""")
            assertFalse(tapped.text, tapped.isError)
            assertTrue(tapped.text, tapped.text.contains("#own-download"))
        } finally {
            com.nujan.helper.phone.PhoneAsks.answerWithoutAsking = null
            server.close()
        }
    }

    private fun callIn(turn: String?, tool: String = "phone_ui"): ToolResult = runBlocking {
        withContext(ToolCaller(CALLER, turn = turn)) { tools.getValue(tool).call(Json.parseToJsonElement("{}").jsonObject) }
    }

    /** Stop ends the turn it was pressed in, as stopped, so the app ends its turn; phone driving stays on for the next. */
    @Test
    fun stopEndsTheTurnItWasPressedInAndTheNextTurnRuns() {
        assertFalse(callIn("turn-1").isError)
        ServiceLocator.phoneDriving.stopTurn()

        val stopped = callIn("turn-1")
        assertTrue(stopped.text, stopped.isError && stopped.stopped && stopped.text.contains("pressed Stop"))
        assertFalse("phone driving itself stays on", ServiceLocator.phoneDriving.stopped.value)

        val next = callIn("turn-2")
        assertFalse(next.text, next.isError)
        ServiceLocator.phoneDriving.turnEnded(CALLER, "turn-2")
    }

    /**
     * Pressed by the shell at the button's place, since reading other apps' windows here would take a UiAutomation, which suppresses
     * every Accessibility service on the phone, the add-on too.
     */
    @Test
    fun theStopButtonOnScreenStopsTheTurnInFront() = runBlocking {
        assumeTrue("needs the shell, to press it", runCatching { ServiceLocator.phoneLink.connection() }.isSuccess)
        callIn("turn-1")
        // A new connection raises Android's notification over where the button is, for a few seconds.
        SystemClock.sleep(HEADS_UP_MS)
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        (instrumentation.targetContext.getSystemService(android.view.WindowManager::class.java)).defaultDisplay.getRealSize(size)
        val x = size.x - (STOP_IN_FROM_EDGE_DP * metrics.density).toInt()
        val y = (STOP_DOWN_DP * metrics.density).toInt()
        ServiceLocator.phoneLink.shell("input tap $x $y")
        val deadline = SystemClock.uptimeMillis() + STOP_WAIT_MS
        while (!ServiceLocator.phoneDriving.turnStopped(CALLER, "turn-1") && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
        assertTrue(ServiceLocator.phoneDriving.turnStopped(CALLER, "turn-1"))
        assertTrue(callIn("turn-1").stopped)
        assertFalse(ServiceLocator.phoneDriving.stopped.value)
    }

    private companion object {
        const val CALLER = "com.example.caller"
        const val SETTLE_MS = 700L
        const val OPEN_WAIT_MS = 8_000L
        const val HEADS_UP_MS = 7_000L
        const val STOP_WAIT_MS = 5_000L
        const val SWITCH_WAIT_MS = 6_000L
        const val POLL_MS = 100L
        const val STOP_IN_FROM_EDGE_DP = 60
        const val STOP_DOWN_DP = 84
        const val PAGE_LOAD_MS = 2_500L
        const val CHROME = "com.android.chrome"
        const val UNREAD_CALLER = "com.example.other"

        /** A download page with an ad unit as web pages carry one: a label, then the ad network's frame, posing as the button. */
        const val AD_PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="font-family:sans-serif;margin:16px"><h1>Sample App 4.2</h1>
            <div style="background:#eee;text-align:center;padding:8px"><p style="font-size:12px">Advertisement</p>
            <div id="aswift_1_host" style="background:#fff;padding:16px"><a href="#ad" style="display:inline-block;background:#1e9e4a;color:#fff;font-size:24px;padding:16px 36px">DOWNLOAD NOW</a></div></div>
            <h2>Download</h2><a href="#own-download" style="display:inline-block;background:#c62828;color:#fff;padding:12px 20px">Download APK</a>
            </body></html>"""
    }
}
