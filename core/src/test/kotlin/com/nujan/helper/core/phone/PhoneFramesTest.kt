package com.nujan.helper.core.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A burst of screen pictures: how it is laid out, timed and compared, and which part of the screen a region takes. */
class PhoneFramesTest {
    private fun still(across: Int, down: Int, light: Float = 0.3f) = FloatArray(across * down) { light }

    /** A grid with a bright square [size] cells wide whose top-left cell is at [x], [y]. */
    private fun withSquare(across: Int, down: Int, x: Int, y: Int, size: Int = 1) = still(across, down).also { grid ->
        for (row in y until y + size) for (column in x until x + size) grid[row * across + column] = 0.9f
    }

    @Test fun `a still screen has changed nowhere`() {
        val change = PhoneFrames.changed(still(36, 80), still(36, 80), 36, 80, 576, 1280)
        assertEquals(0.0, change.share, 0.0)
        assertNull(change.where)
    }

    @Test fun `one cell that changed counts, and where says which part of the picture it is`() {
        val change = PhoneFrames.changed(still(36, 80), withSquare(36, 80, 10, 20), 36, 80, 576, 1280)
        assertTrue(change.share > 0.0)
        assertEquals(listOf(160, 320, 16, 16), change.where)
    }

    @Test fun `a thing that moved is found where it was and where it went`() {
        val change = PhoneFrames.changed(withSquare(36, 80, 2, 5), withSquare(36, 80, 6, 5), 36, 80, 576, 1280)
        assertEquals(listOf(32, 80, 80, 16), change.where)
    }

    @Test fun `a blinking cursor, a dark line one pixel wide across a light cell, is not movement`() {
        val cursor = still(36, 80, light = 0.95f).also { it[100] -= 0.95f / PhoneFrames.CELL_PX }
        assertNull(PhoneFrames.changed(still(36, 80, light = 0.95f), cursor, 36, 80, 576, 1280).where)
    }

    @Test fun `a thing a few pixels across, against its background, is movement`() {
        val dot = still(36, 80, light = 0.95f).also { it[100] -= 0.5f * 4 / PhoneFrames.CELL_PX }
        assertTrue(PhoneFrames.changed(still(36, 80, light = 0.95f), dot, 36, 80, 576, 1280).where != null)
    }

    @Test fun `a region's change is placed in the whole picture's coordinates`() {
        val change = PhoneFrames.changed(still(10, 10), withSquare(10, 10, 0, 0), 10, 10, 160, 160, originX = 300, originY = 500)
        assertEquals(listOf(300, 500, 16, 16), change.where)
    }

    @Test fun `grids of different sizes are reported as all changed rather than compared`() {
        assertEquals(1.0, PhoneFrames.changed(still(4, 4), still(5, 5), 5, 5, 80, 80).share, 0.0)
    }

    @Test fun `the time between frames is held to what the phone allows and the longest burst`() {
        assertEquals(PhoneFrames.EVERY_MS_DEFAULT, PhoneFrames.everyMs(null, 4))
        assertEquals(PhoneFrames.EVERY_MS_MIN, PhoneFrames.everyMs(50, 4))
        assertEquals(PhoneFrames.EVERY_MS_MAX, PhoneFrames.everyMs(10_000, 3))
        assertEquals(PhoneFrames.SPAN_MS_MAX / 5, PhoneFrames.everyMs(10_000, 6))
    }

    @Test fun `up to three frames sit in one row, more in rows of three, all within the longest side`() {
        val three = PhoneFrames.layout(3, 576, 1280, 1280)
        assertEquals(3 to 1, three.columns to three.rows)
        val six = PhoneFrames.layout(6, 576, 1280, 1280)
        assertEquals(3 to 2, six.columns to six.rows)
        assertTrue(six.width <= 1280 && six.height <= 1280)
        assertEquals(six.tileWidth + six.gap to 0, six.corner(1))
        assertEquals(0 to six.tileHeight + six.gap, six.corner(3))
    }

    @Test fun `a small burst is never drawn larger than the screen was`() {
        val layout = PhoneFrames.layout(2, 100, 50, 1280)
        assertEquals(100 to 50, layout.tileWidth to layout.tileHeight)
    }

    @Test fun `the summary says nothing moved, or where the changes were`() {
        assertEquals("Nothing on the screen changed in 3 pictures over 1.2 s.", PhoneFrames.summary(listOf(PhoneFrames.Change(0.0, null), PhoneFrames.Change(0.0, null)), 1_200))
        val said = PhoneFrames.summary(
            listOf(PhoneFrames.Change(0.01, listOf(10, 20, 30, 40)), PhoneFrames.Change(0.0, null), PhoneFrames.Change(0.02, listOf(100, 20, 30, 80))),
            1_800,
        )
        assertEquals("The screen changed between 2 of 3 pairs of pictures over 1.8 s, within [10, 20, 120, 80].", said)
    }

    @Test fun `a region is held inside the picture, and one off it or too small is refused`() {
        assertEquals(listOf(500, 1200, 76, 80), PhoneFrames.region(listOf(500.0, 1200.0, 300.0, 300.0), 576, 1280))
        assertNull(PhoneFrames.region(listOf(900.0, 100.0, 50.0, 50.0), 576, 1280))
        assertNull(PhoneFrames.region(listOf(10.0, 10.0, 8.0, 8.0), 576, 1280))
        assertNull(PhoneFrames.region(listOf(10.0, 10.0), 576, 1280))
    }

    @Test fun `a region is enlarged in whole steps, at most three times`() {
        assertEquals(3, PhoneFrames.enlargement(100, 60, 1280))
        assertEquals(2, PhoneFrames.enlargement(500, 300, 1280))
        assertEquals(1, PhoneFrames.enlargement(900, 300, 1280))
    }
}
