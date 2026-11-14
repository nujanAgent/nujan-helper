package com.nujan.helper.hands

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.RoundedCorner
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What the screen shows while an app drives the phone: a band of light running round the screen's edge, and the app's
 * cursor at each place it touches. The cursor glides there on a shallow arc, ripples where it taps, traces a swipe and
 * stirs while the app thinks. The view takes no touches, and the cursor can be hidden while a picture of the screen is
 * taken, so it never covers what the picture is for.
 */
internal class DrivingView(context: Context) : View(context) {

    /** The name on the cursor's tag: the app driving the phone. */
    var driver: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Whether the cursor and its tag are drawn; the light always is. */
    var cursorShown: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val turning = Matrix()
    private val band = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
        strokeWidth = 1.6f * dp
    }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * dp
        typeface = Typeface.DEFAULT_BOLD
    }
    private val arrow = Path().apply {
        ARROW.forEachIndexed { index, (x, y) -> if (index == 0) moveTo(x * dp, y * dp) else lineTo(x * dp, y * dp) }
        close()
    }
    private val origin = IntArray(2)

    private var leg: Leg? = null
    private var legStart = 0L
    private var swipe: Leg? = null
    private var swipeMs = 0L
    private var tapAt = 0L
    private var lastActionAt = 0L

    /** Asks for a frame each vsync while the view is on screen; every frame is drawn from the clock alone. */
    private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clock.start()
    }

    override fun onDetachedFromWindow() {
        clock.cancel()
        super.onDetachedFromWindow()
    }

    /** The app touches the screen at ([x], [y]), screen pixels, and moves to ([toX], [toY]) over [ms] when that differs. */
    fun touchAt(x: Float, y: Float, toX: Float, toY: Float, ms: Long) {
        getLocationOnScreen(origin)
        val target = x - origin[0] to y - origin[1]
        val now = SystemClock.uptimeMillis()
        leg = Leg(position(now) ?: (width / 2f to height.toFloat()), target)
        legStart = now
        swipe = if (hypot(toX - x, toY - y) > MOVE_THRESHOLD * dp) Leg(target, toX - origin[0] to toY - origin[1], arc = 0f) else null
        swipeMs = ms.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        tapAt = if (swipe == null) now + TRAVEL_MS else 0L
        lastActionAt = now
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        drawLight(canvas, now)
        if (cursorShown) drawCursor(canvas, now)
    }

    private fun drawLight(canvas: Canvas, now: Long) {
        val turn = (now % LIGHT_TURN_MS) / LIGHT_TURN_MS.toFloat() * 360f
        val breath = LIGHT_DIM + (1f - LIGHT_DIM) * (0.5f + 0.5f * sin(2 * PI * now / LIGHT_BREATH_MS).toFloat())
        val shader = SweepGradient(width / 2f, height / 2f, LIGHT_COLOURS, null)
        turning.setRotate(turn, width / 2f, height / 2f)
        shader.setLocalMatrix(turning)
        lightPaint.shader = shader
        val corner = screenCorner()
        BANDS.forEach { (widthDp, alpha) ->
            val w = widthDp * dp
            lightPaint.strokeWidth = w
            lightPaint.alpha = (255 * alpha * breath).toInt()
            band.set(w / 2f, w / 2f, width - w / 2f, height - w / 2f)
            val r = max(corner - w / 2f, 0f)
            canvas.drawRoundRect(band, r, r, lightPaint)
        }
    }

    private fun drawCursor(canvas: Canvas, now: Long) {
        val at = position(now) ?: return
        val sinceTap = now - tapAt
        if (tapAt > 0L && sinceTap in 0 until RIPPLE_MS) {
            val done = sinceTap / RIPPLE_MS.toFloat()
            val radius = RIPPLE_DP * dp * (0.25f + 0.75f * done)
            fill.shader = null
            fill.color = ACCENT
            fill.alpha = (255 * 0.3f * (1f - done)).toInt()
            canvas.drawCircle(at.first, at.second, radius, fill)
        }
        val trail = swipe
        if (trail != null && now - legStart > TRAVEL_MS) {
            fill.shader = LinearGradient(
                trail.from.first, trail.from.second, at.first, at.second,
                Color.TRANSPARENT, (ACCENT and 0x00FFFFFF) or (0x8C shl 24), Shader.TileMode.CLAMP,
            )
            fill.strokeWidth = TRAIL_DP * dp
            fill.strokeCap = Paint.Cap.ROUND
            fill.style = Paint.Style.STROKE
            canvas.drawLine(trail.from.first, trail.from.second, at.first, at.second, fill)
            fill.style = Paint.Style.FILL
            fill.shader = null
        }
        val idle = now - lastActionAt > THINKING_AFTER_MS
        val tilt = if (idle) STIR_DEGREES * sin(2 * PI * now / STIR_MS).toFloat() else 0f
        canvas.save()
        canvas.translate(at.first, at.second)
        canvas.rotate(tilt)
        canvas.save()
        canvas.translate(1.2f * dp, 1.8f * dp)
        fill.color = Color.BLACK
        fill.alpha = 72
        canvas.drawPath(arrow, fill)
        canvas.restore()
        fill.color = ACCENT
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, rim)
        canvas.restore()
        drawTag(canvas, at)
    }

    /**
     * The driving app's name beside the cursor: below and right of the arrow's tip, to the tip's left near the screen's
     * right edge and above it near the foot, never off the screen, so the name is not cut off.
     */
    private fun drawTag(canvas: Canvas, at: Pair<Float, Float>) {
        val name = driver.ifBlank { return }
        val padX = 8f * dp
        val tagWidth = tagText.measureText(name) + 2 * padX
        val tagHeight = tagText.textSize + 6f * dp
        var left = at.first + TAG_DX * dp
        if (left + tagWidth > width) left = at.first - TAG_FLIP * dp - tagWidth
        var top = at.second + TAG_DY * dp
        if (top + tagHeight > height) top = at.second - TAG_FLIP * dp - tagHeight
        left = left.coerceIn(0f, max(width - tagWidth, 0f))
        top = top.coerceIn(0f, max(height - tagHeight, 0f))
        band.set(left, top, left + tagWidth, top + tagHeight)
        fill.color = TAG_FILL
        canvas.drawRoundRect(band, tagHeight / 2f, tagHeight / 2f, fill)
        lightPaint.shader = null
        lightPaint.color = ACCENT
        lightPaint.alpha = 255
        lightPaint.strokeWidth = 1f * dp
        canvas.drawRoundRect(band, tagHeight / 2f, tagHeight / 2f, lightPaint)
        canvas.drawText(name, left + padX, top + tagHeight / 2f - (tagText.ascent() + tagText.descent()) / 2f, tagText)
    }

    /** Where the cursor is at [now], or null before the app's first touch. */
    private fun position(now: Long): Pair<Float, Float>? {
        val current = leg ?: return null
        val moved = min((now - legStart) / TRAVEL_MS.toFloat(), 1f)
        val trail = swipe
        if (moved < 1f || trail == null) return current.at(ease(moved))
        val swiped = min((now - legStart - TRAVEL_MS) / swipeMs.toFloat(), 1f)
        return trail.at(ease(swiped))
    }

    /** The radius of the screen's own corners, so the light follows them; a rounded guess where Android does not say. */
    private fun screenCorner(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.let { return it.toFloat() }
        }
        return FALLBACK_CORNER_DP * dp
    }

    /** One move from [from] to [to], bowing out by [arc] of its length, the way a hand moves. */
    private class Leg(val from: Pair<Float, Float>, val to: Pair<Float, Float>, arc: Float = ARC) {
        private val control = ((from.first + to.first) / 2f - (to.second - from.second) * arc) to
            ((from.second + to.second) / 2f + (to.first - from.first) * arc)

        fun at(t: Float): Pair<Float, Float> {
            val u = 1f - t
            return (u * u * from.first + 2f * u * t * control.first + t * t * to.first) to
                (u * u * from.second + 2f * u * t * control.second + t * t * to.second)
        }
    }

    private companion object {
        fun ease(t: Float): Float = t * t * (3f - 2f * t)

        const val ACCENT = 0xFFFF5E1A.toInt()
        const val TAG_FILL = 0xF2141414.toInt()
        val LIGHT_COLOURS = intArrayOf(ACCENT, 0xFFFFB089.toInt(), 0xFFFF9D6A.toInt(), 0xFFE8743F.toInt(), ACCENT)

        /** The light's bands in dp with their strength, widest and faintest first; each fades inward from the edge. */
        val BANDS = listOf(28f to 0.06f, 16f to 0.12f, 8f to 0.26f, 3f to 0.95f)

        /** The arrow's corners in dp, tip first. */
        val ARROW = listOf(0f to 0f, 0f to 19f, 4.6f to 14.8f, 7.6f to 21.6f, 10.4f to 20.4f, 7.4f to 13.8f, 13.4f to 13.8f)

        const val ARC = 0.16f
        const val LIGHT_TURN_MS = 3_600L
        const val LIGHT_BREATH_MS = 2_800.0
        const val LIGHT_DIM = 0.7f
        const val TRAVEL_MS = 260L
        const val RIPPLE_MS = 650L
        const val RIPPLE_DP = 26f
        const val TRAIL_DP = 10f
        const val MIN_SWIPE_MS = 200L
        const val MAX_SWIPE_MS = 1_200L
        const val MOVE_THRESHOLD = 8f
        const val THINKING_AFTER_MS = 1_200L
        const val STIR_MS = 1_040.0
        const val STIR_DEGREES = 7f
        const val FALLBACK_CORNER_DP = 28f
        const val TAG_DX = 14f
        const val TAG_DY = 20f
        const val TAG_FLIP = 6f
    }
}

/** A ring with a turning arc in NuJan's orange, beside the Stop button while an app drives the phone. */
internal class WorkingRingView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()
    private val clock = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1_100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clock.start()
    }

    override fun onDetachedFromWindow() {
        clock.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = (18f * dp).toInt()
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val inset = paint.strokeWidth / 2f
        box.set(inset, inset, width - inset, height - inset)
        paint.color = 0x38FF5E1A
        canvas.drawArc(box, 0f, 360f, false, paint)
        paint.color = 0xFFFF5E1A.toInt()
        canvas.drawArc(box, clock.animatedValue as Float, 110f, false, paint)
    }
}
