package com.nujan.helper.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The Helper's icons: plain strokes on a 24-unit grid, tinted where they are drawn. */
object HelperIcons {
    val Powers = icon("M13 3L5 14h6l-1 7 8-11h-6l1-7z")
    val Activity = icon("M3 12h4l3-8 4 16 3-8h4")
    val Plugins = icon("M9 3v4", "M15 3v4", "M5 7h14v5a7 7 0 0 1-14 0z", "M12 19v2")
    val Rules = icon("M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7l8-4z", "M9 12l2 2 4-4")
    val Toolbox = icon("M3 5h18v14H3z", "M7 10l3 2-3 2", "M12 15h5")
    val Usb = icon("M12 3v13", "M9 6l3-3 3 3", "M12 13l-4-3V8", "M12 11l4-3V6", "M10 19a2 2 0 1 0 4 0 2 2 0 1 0-4 0")
    val Phone = icon("M7 2h10a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z", "M11 18h2")
    val Apps = icon("M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z")
    val Chevron = icon("M9 6l6 6-6 6")
    val Back = icon("M15 6l-6 6 6 6")

    private fun icon(vararg paths: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { data ->
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}
