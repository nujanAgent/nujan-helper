package com.nujan.helper.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The Accessibility service: reads the window in front, makes gestures and takes a picture. While it is in use it lights
 * the screen's edge, shows the driving app's cursor where it touches ([DrivingView]) and offers Stop. It acts only when the
 * Helper calls ([HandsProvider]) and reads no text from a password field.
 *
 * It is on only while it is needed. Banking and payment apps refuse to run beside an Accessibility service from outside a
 * store, so it turns itself off whenever an app that holds money or passwords comes to the front, and, when the Helper can
 * turn it on again for the next drive, as soon as a drive ends.
 */
class HandsService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var frame: DrivingView? = null
    private var stop: View? = null
    private var stopName: TextView? = null
    private val hideFrame = Runnable { endDrive() }

    override fun onServiceConnected() {
        HandsState.on(this)
        // Checked before the service answers the Helper, so it never reports itself on while such an app is in front.
        if (offFor(rootInActiveWindow?.packageName?.toString())) return
        running = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) offFor(event.packageName?.toString())
    }

    /** Turns the service off when [packageName], now in front, holds money or passwords; whether it did. */
    private fun offFor(packageName: String?): Boolean {
        if (packageName == null || packageName !in HandsState.guarded(this)) return false
        HandsState.turningItselfOff(this, packageName)
        turnOff()
        return true
    }

    /** The drive is over: the frame comes down, and the service turns itself off when the Helper can turn it on again. */
    private fun endDrive() {
        removeFrame()
        if (!HandsState.offWhenIdle(this)) return
        HandsState.turningItselfOff(this, forApp = null)
        turnOff()
    }

    private fun turnOff() {
        running = null
        main.post { removeFrame() }
        disableSelf()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        running = null
        main.post { removeFrame() }
        super.onDestroy()
    }

    /** The window in front as `uiautomator dump` would write it, with the password fields' text left out. */
    fun tree(): String? {
        val root = rootInActiveWindow ?: return null
        val out = StringBuilder("<hierarchy>")
        write(root, out, 0)
        return out.append("</hierarchy>").toString()
    }

    private fun write(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        // This service listens only for window changes, to cost the phone nothing, so a node Android cached may be out of date.
        if (depth > MAX_DEPTH || !node.refresh() || !node.isVisibleToUser) return
        val box = Rect().also(node::getBoundsInScreen)
        out.append("<node")
        attribute(out, "text", if (node.isPassword) "" else node.text?.toString().orEmpty())
        attribute(out, "resource-id", node.viewIdResourceName.orEmpty())
        attribute(out, "class", node.className?.toString().orEmpty())
        attribute(out, "package", node.packageName?.toString().orEmpty())
        attribute(out, "content-desc", node.contentDescription?.toString().orEmpty())
        attribute(out, "clickable", node.isClickable.toString())
        attribute(out, "long-clickable", node.isLongClickable.toString())
        attribute(out, "scrollable", node.isScrollable.toString())
        attribute(out, "editable", node.isEditable.toString())
        attribute(out, "checked", node.isChecked.toString())
        attribute(out, "selected", node.isSelected.toString())
        attribute(out, "focused", node.isFocused.toString())
        attribute(out, "enabled", node.isEnabled.toString())
        attribute(out, "password", node.isPassword.toString())
        attribute(out, "bounds", "[${box.left},${box.top}][${box.right},${box.bottom}]")
        out.append('>')
        for (i in 0 until node.childCount) node.getChild(i)?.let { write(it, out, depth + 1) }
        out.append("</node>")
    }

    private fun attribute(out: StringBuilder, name: String, value: String) {
        out.append(' ').append(name).append("=\"")
        value.forEach { c ->
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\n' -> out.append("&#10;")
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    fun inFront(): String? = rootInActiveWindow?.packageName?.toString()

    /** The top of the keyboard on screen, or -1 while none shows. */
    fun keyboardTop(): Int {
        val box = Rect()
        val keyboard = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return -1
        keyboard.getBoundsInScreen(box)
        return if (box.isEmpty) -1 else box.top
    }

    /** Opens what [uri] describes. An Accessibility service may start an activity from the background; the Helper may not. */
    fun open(uri: String): Boolean = runCatching {
        startActivity(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /** A press from one point to another, finished or cancelled; false when Android did not take it. */
    fun gesture(x: Float, y: Float, toX: Float, toY: Float, ms: Long): Boolean {
        main.post { frame?.touchAt(x, y, toX, toY, ms) }
        val path = Path().apply {
            moveTo(x, y)
            lineTo(toX, toY)
        }
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val sent = dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceAtLeast(1))).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok.set(true)
                    done.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) = done.countDown()
            },
            main,
        )
        if (!sent) return false
        done.await(ms + GESTURE_WAIT_MS, TimeUnit.MILLISECONDS)
        return ok.get()
    }

    fun global(action: String): Boolean = performGlobalAction(
        when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return false
        },
    )

    fun setFocusedText(text: String): Boolean {
        val field = rootInActiveWindow?.let { focusedField(it, 0) } ?: return false
        val box = Rect().also(field::getBoundsInScreen)
        if (!box.isEmpty) main.post { frame?.touchAt(box.exactCenterX(), box.exactCenterY(), box.exactCenterX(), box.exactCenterY(), 0) }
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** The field taking input, read from the tree: `findFocus` answers from a cache that this service, hearing only window changes, cannot keep true. */
    private fun focusedField(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH || !node.refresh()) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { focusedField(it, depth + 1) }?.let { return it }
        return null
    }

    /** A picture of the screen, as a JPEG small enough for a call to carry, or why there is none. */
    fun picture(): Pair<ByteArray?, String?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null to "Android older than 11 cannot take a picture this way."
        val done = CountDownLatch(1)
        val bytes = AtomicReference<ByteArray?>()
        val problem = AtomicReference<String?>()
        hideCursorForPicture()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    bytes.set(hardware?.let(::jpeg))
                    screenshot.hardwareBuffer.close()
                    done.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    problem.set(
                        if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) "The app in front keeps its screen private." else "Android did not give a picture (code $errorCode).",
                    )
                    done.countDown()
                }
            },
        )
        done.await(PICTURE_WAIT_MS, TimeUnit.MILLISECONDS)
        main.post { frame?.cursorShown = true }
        return bytes.get() to problem.get()
    }

    /** Takes the cursor off the screen until the picture is taken, so it covers nothing the picture is for. */
    private fun hideCursorForPicture() {
        val hidden = CountDownLatch(1)
        main.post {
            val screen = frame
            if (screen == null || !screen.cursorShown) {
                hidden.countDown()
            } else {
                screen.cursorShown = false
                main.postDelayed({ hidden.countDown() }, CURSOR_OFF_MS)
            }
        }
        hidden.await(CURSOR_OFF_MS + PICTURE_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private fun jpeg(hardware: Bitmap): ByteArray {
        val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
        val long = maxOf(software.width, software.height)
        val small = if (long > PICTURE_LONG_SIDE) {
            Bitmap.createScaledBitmap(software, software.width * PICTURE_LONG_SIDE / long, software.height * PICTURE_LONG_SIDE / long, true)
        } else {
            software
        }
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    }

    /**
     * Shows the frame and Stop, named for [app], the app driving the phone, until [done] or for a while after the last
     * call when that app does not say it is done.
     */
    fun inUse(app: String?) {
        main.post {
            showFrame(app)
            main.removeCallbacks(hideFrame)
            main.postDelayed(hideFrame, FRAME_MS)
        }
    }

    /** Takes the frame and Stop down now: the app driving the phone has finished. */
    fun done() {
        main.post {
            main.removeCallbacks(hideFrame)
            endDrive()
        }
    }

    private fun showFrame(app: String?) {
        frame?.driver = app.orEmpty()
        stopName?.text = driverName(app)
        stop?.contentDescription = stopDescription(app)
        if (frame != null) return
        val windows = getSystemService(WindowManager::class.java) ?: return
        val screen = DrivingView(this).apply { driver = app.orEmpty() }
        windows.addView(
            screen,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            },
        )
        val name = TextView(this).apply {
            text = driverName(app)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, dp(10), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val stopChip = TextView(this).apply {
            text = "Stop"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                setColor(STOP_COLOR)
                cornerRadius = dp(14).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val button = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(PILL_COLOR)
                setStroke(dp(1), PILL_EDGE)
                cornerRadius = dp(24).toFloat()
            }
            elevation = dp(6).toFloat()
            contentDescription = stopDescription(app)
            setOnClickListener { pressStop() }
            addView(WorkingRingView(this@HandsService).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            addView(name)
            addView(stopChip)
        }
        windows.addView(
            button,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(12)
                y = dp(64)
            },
        )
        frame = screen
        stop = button
        stopName = name
    }

    private fun removeFrame() {
        val windows = getSystemService(WindowManager::class.java) ?: return
        frame?.let { runCatching { windows.removeViewImmediate(it) } }
        stop?.let { runCatching { windows.removeViewImmediate(it) } }
        frame = null
        stop = null
        stopName = null
    }

    private fun pressStop() {
        sendBroadcast(Intent(ACTION_STOP).setPackage(HELPER), PERMISSION)
        main.removeCallbacks(hideFrame)
        endDrive()
    }

    /** The name on the pill beside Stop: the app driving the phone. */
    private fun driverName(app: String?): String = if (app.isNullOrBlank()) "An app is using this phone" else app

    private fun stopDescription(app: String?): String =
        if (app.isNullOrBlank()) "Stop what is using this phone now" else "Stop what $app is doing on this phone now"

    private fun dp(value: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        @Volatile var running: HandsService? = null
            private set

        const val HELPER = "com.nujan.helper"
        private const val ACTION_STOP = "com.nujan.helper.action.STOP"
        private const val PERMISSION = "com.nujan.helper.permission.HANDS"
        private const val MAX_DEPTH = 60
        private const val GESTURE_WAIT_MS = 3_000L
        private const val PICTURE_WAIT_MS = 4_000L
        private const val PICTURE_LONG_SIDE = 1280
        private const val JPEG_QUALITY = 72
        private const val FRAME_MS = 30_000L
        private const val STOP_COLOR = 0xFFE5484D.toInt()
        private const val PILL_COLOR = 0xF2141414.toInt()
        private const val PILL_EDGE = 0x99FF5E1A.toInt()

        /** How long the cursor is off the screen before a picture is taken: two frames at 60 Hz, and a margin. */
        private const val CURSOR_OFF_MS = 50L
    }
}
