package com.nujan.helper.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.em

/**
 * The Helper's colours: midnight blue grounds with one teal accent, so it reads as its own app beside NuJan's orange.
 * Every text colour holds at least 7:1 against [Card]. A state is always said in words too, so colour is never the only
 * thing that tells it.
 */
object Midnight {
    val Ground = Color(0xFF0B1222)
    val Card = Color(0xFF131C33)
    val CardRaised = Color(0xFF1B2643)
    val Line = Color(0xFF2B3759)
    val Text = Color(0xFFE6EAF4)
    val TextDim = Color(0xFFA6B1CC)
    val Accent = Color(0xFF4FE0C0)
    val OnAccent = Color(0xFF04231C)
    val Ok = Color(0xFF7BE8A9)
    val Warn = Color(0xFFFFC56E)
    val Error = Color(0xFFFF9090)
}

/**
 * Text that sets only its size keeps the body style's line height, so that height is a multiple of the size rather than
 * a fixed 24sp that spreads small text apart. The small body style moves with it, since a text field's label animates
 * between the two and cannot mix em with sp.
 */
private val Body = Typography().let { it.copy(bodyLarge = it.bodyLarge.copy(lineHeight = 1.4.em), bodySmall = it.bodySmall.copy(lineHeight = 1.4.em)) }

@Composable
fun HelperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Midnight.Accent,
            onPrimary = Midnight.OnAccent,
            background = Midnight.Ground,
            onBackground = Midnight.Text,
            surface = Midnight.Card,
            onSurface = Midnight.Text,
            surfaceVariant = Midnight.CardRaised,
            onSurfaceVariant = Midnight.TextDim,
            outline = Midnight.Line,
            error = Midnight.Error,
        ),
        typography = Body,
        content = content,
    )
}
