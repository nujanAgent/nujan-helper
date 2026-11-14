package com.nujan.helper.core.phone

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Several pictures of the screen a moment apart, laid out as one picture and compared, so a model sees what one picture
 * misses: a toast, a loading screen, an animation, a screen that is not changing. Coordinates are the picture's, the space
 * phone_act takes.
 *
 * Pictures are compared as grids of brightness, a cell [CELL_PX] picture pixels square. A still screen draws the same
 * pixels each time, so a cell that changed is something that moved; a line one pixel wide crossing a cell, such as a text
 * field's blinking cursor, stays under [CHANGE_LIGHT], and a thing a few pixels across does not.
 */
object PhoneFrames {
    const val BURST_MAX = 6
    /** Android lets an accessibility service take a picture about three times a second. */
    const val EVERY_MS_MIN = 400L
    const val EVERY_MS_DEFAULT = 600L
    const val EVERY_MS_MAX = 3_000L
    const val SPAN_MS_MAX = 12_000L
    const val CELL_PX = 16
    const val CHANGE_LIGHT = 0.08f
    /** A region is shown at most this many times larger, so its pixels stay readable rather than blurred. */
    const val REGION_ENLARGE_MAX = 3
    const val REGION_MIN_PX = 16

    data class Layout(val columns: Int, val rows: Int, val tileWidth: Int, val tileHeight: Int, val gap: Int) {
        val width: Int get() = columns * tileWidth + (columns - 1) * gap
        val height: Int get() = rows * tileHeight + (rows - 1) * gap

        /** Where frame [index] sits in the picture: reading order, left to right and then down. */
        fun corner(index: Int): Pair<Int, Int> = (index % columns) * (tileWidth + gap) to (index / columns) * (tileHeight + gap)
    }

    /** The time between frames for a burst of [count]: what was asked, held to what the phone allows and to [SPAN_MS_MAX]. */
    fun everyMs(asked: Long?, count: Int): Long =
        (asked ?: EVERY_MS_DEFAULT).coerceIn(EVERY_MS_MIN, min(EVERY_MS_MAX, SPAN_MS_MAX / max(1, count - 1)))

    /** How [count] frames of [width] by [height] fit within [maxSide]: one row for two or three, else rows of three. */
    fun layout(count: Int, width: Int, height: Int, maxSide: Int, gap: Int = 4): Layout {
        val columns = if (count <= 3) count else 3
        val rows = ceil(count / columns.toDouble()).toInt()
        val scale = min(
            min((maxSide - gap * (columns - 1)).toDouble() / (columns * width), (maxSide - gap * (rows - 1)).toDouble() / (rows * height)),
            1.0,
        )
        return Layout(columns, rows, max(1, floor(width * scale).toInt()), max(1, floor(height * scale).toInt()), gap)
    }

    /** The cells of a brightness grid across and down a picture of [width] by [height]. */
    fun grid(width: Int, height: Int): Pair<Int, Int> =
        max(1, ceil(width / CELL_PX.toDouble()).toInt()) to max(1, ceil(height / CELL_PX.toDouble()).toInt())

    /** How much of the picture differs between two brightness grids, as a share, and where: [x, y, width, height]. */
    data class Change(val share: Double, val where: List<Int>?)

    /**
     * How much differs between [before] and [now], grids of [across] by [down] cells each 0 to 1 bright, taken from a picture
     * [width] by [height] placed at [originX], [originY].
     */
    fun changed(before: FloatArray, now: FloatArray, across: Int, down: Int, width: Int, height: Int, originX: Int = 0, originY: Int = 0): Change {
        if (before.size != now.size || now.size != across * down) return Change(1.0, listOf(originX, originY, width, height))
        var changed = 0
        var left = across
        var top = down
        var right = -1
        var bottom = -1
        for (y in 0 until down) for (x in 0 until across) {
            if (abs(now[y * across + x] - before[y * across + x]) > CHANGE_LIGHT) {
                changed++
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
        val share = (changed.toDouble() / (across * down) * 10_000).roundToInt() / 10_000.0
        if (changed == 0) return Change(share, null)
        val cellW = width.toDouble() / across
        val cellH = height.toDouble() / down
        return Change(
            share,
            listOf(
                originX + (left * cellW).roundToInt(),
                originY + (top * cellH).roundToInt(),
                ((right - left + 1) * cellW).roundToInt(),
                ((bottom - top + 1) * cellH).roundToInt(),
            ),
        )
    }

    /** One sentence on a burst: whether anything moved and where, so a model does not have to compare the pictures itself. */
    fun summary(changes: List<Change>, spanMs: Long): String {
        val moved = changes.mapNotNull { it.where }
        val seconds = "%.1f".format(java.util.Locale.ROOT, spanMs / 1000.0)
        if (moved.isEmpty()) return "Nothing on the screen changed in ${changes.size + 1} pictures over $seconds s."
        val left = moved.minOf { it[0] }
        val top = moved.minOf { it[1] }
        val right = moved.maxOf { it[0] + it[2] }
        val bottom = moved.maxOf { it[1] + it[3] }
        return "The screen changed between ${moved.size} of ${changes.size} pairs of pictures over $seconds s, " +
            "within [$left, $top, ${right - left}, ${bottom - top}]."
    }

    /**
     * The part of a picture [width] by [height] that `region` names, [x, y, width, height], held inside it; null when it lies
     * off the picture or is too small to show.
     */
    fun region(asked: List<Double>, width: Int, height: Int): List<Int>? {
        if (asked.size != 4) return null
        val x = asked[0].roundToInt().coerceIn(0, width)
        val y = asked[1].roundToInt().coerceIn(0, height)
        val right = (asked[0] + asked[2]).roundToInt().coerceIn(0, width)
        val bottom = (asked[1] + asked[3]).roundToInt().coerceIn(0, height)
        if (right - x < REGION_MIN_PX || bottom - y < REGION_MIN_PX) return null
        return listOf(x, y, right - x, bottom - y)
    }

    /** How many times larger a region [width] by [height] is shown within [maxSide], in whole steps. */
    fun enlargement(width: Int, height: Int, maxSide: Int): Int =
        (maxSide / max(1, max(width, height))).coerceIn(1, REGION_ENLARGE_MAX)
}
