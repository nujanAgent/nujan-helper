package com.nujan.helper.mcp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.nujan.helper.core.phone.PhoneFrames
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * The screen as phone_screen shows it: one picture, a burst of pictures laid out as one, or part of the screen enlarged.
 * Every picture is the size of the screen times the tools' scale, whatever size it was taken at, so a point in it is the
 * point phone_act takes: the Hands add-on hands over a picture already made smaller and the shell a full-size one.
 */
internal object PhoneShots {
    private const val JPEG_QUALITY = 72
    private const val BLACK_SAMPLES = 24
    private const val MIN_LIT_SAMPLES = 8
    private const val GAP_PX = 4
    private const val NUMBER_TEXT_PX = 22f

    class Shot(val jpeg: ByteArray, val width: Int, val height: Int, val mostlyBlack: Boolean)

    /** [bytes] decoded at [width] by [height], the picture's size; null when they are not a picture. */
    fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        val taken = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return if (taken.width == width && taken.height == height) taken else Bitmap.createScaledBitmap(taken, width, height, true)
    }

    fun encode(picture: Bitmap): Shot {
        val out = ByteArrayOutputStream()
        picture.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val step = (picture.width / BLACK_SAMPLES).coerceAtLeast(1)
        var lit = 0
        for (x in 0 until picture.width step step) for (y in 0 until picture.height step step) {
            if (picture.getPixel(x, y) and 0xffffff > 0x101010) lit++
        }
        return Shot(out.toByteArray(), picture.width, picture.height, mostlyBlack = lit < MIN_LIT_SAMPLES)
    }

    /** The part of [picture] at [rect], [x, y, width, height], enlarged [times] with its pixels kept sharp. */
    fun region(picture: Bitmap, rect: List<Int>, times: Int): Bitmap {
        val part = Bitmap.createBitmap(picture, rect[0], rect[1], rect[2], rect[3])
        return if (times <= 1) part else Bitmap.createScaledBitmap(part, rect[2] * times, rect[3] * times, false)
    }

    /** [picture] as a grid of brightness, each cell [PhoneFrames.CELL_PX] picture pixels square, averaged by scaling down. */
    fun cells(picture: Bitmap): FloatArray {
        val (across, down) = PhoneFrames.grid(picture.width, picture.height)
        val small = Bitmap.createScaledBitmap(picture, across, down, true)
        val pixels = IntArray(across * down)
        small.getPixels(pixels, 0, across, 0, 0, across, down)
        return FloatArray(pixels.size) { i ->
            val p = pixels[i]
            (0.2126f * Color.red(p) + 0.7152f * Color.green(p) + 0.0722f * Color.blue(p)) / 255f
        }
    }

    /** [frames] laid out as [layout] says, each numbered in its top-left corner, on white. */
    fun sheet(frames: List<Bitmap>, layout: PhoneFrames.Layout): Bitmap {
        val sheet = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = NUMBER_TEXT_PX
            isFakeBoldText = true
        }
        val plate = Paint().apply { color = Color.BLACK }
        frames.forEachIndexed { index, frame ->
            val (x, y) = layout.corner(index)
            canvas.drawBitmap(frame, null, Rect(x, y, x + layout.tileWidth, y + layout.tileHeight), Paint(Paint.FILTER_BITMAP_FLAG))
            val label = (index + 1).toString()
            val width = text.measureText(label)
            canvas.drawRect(x.toFloat(), y.toFloat(), x + width + NUMBER_TEXT_PX * 0.6f, y + NUMBER_TEXT_PX * 1.3f, plate)
            canvas.drawText(label, x + NUMBER_TEXT_PX * 0.3f, y + NUMBER_TEXT_PX, text)
        }
        return sheet
    }

    fun gap(): Int = GAP_PX

    /** [size] times [scale], the picture's side for a screen side. */
    fun scaled(size: Int, scale: Double): Int = (size * scale).roundToInt().coerceAtLeast(1)
}
