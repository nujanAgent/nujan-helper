package com.nujan.helper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * [read]'s value, read again every [everyMs] while the screen is started, and at once when one of [keys] changes. For
 * state that changes without an event a screen can hear: installs, jobs, boards plugged in.
 */
@Composable
fun <T> polled(initial: T, vararg keys: Any?, everyMs: Long = 2_000, read: suspend () -> T): State<T> {
    val lifecycle = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(read)
    return produceState(initial, lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = latest()
                delay(everyMs)
            }
        }
    }
}

/** How a state line reads: done, waiting on something, broken, or plain information. */
enum class Tone(val color: Color) { OK(Midnight.Ok), WARN(Midnight.Warn), ERROR(Midnight.Error), PLAIN(Midnight.TextDim) }

/** One line of state under a card's title, in its tone's colour. */
data class StateLine(val text: String, val tone: Tone = Tone.PLAIN)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = Midnight.TextDim,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A rounded card on the midnight ground. */
@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, clickLabel: String? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        color = Midnight.Card,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

/**
 * A power or a plugin: what it is, what it lets NuJan do, how it stands now, and the one thing to do next. The whole card
 * opens its page when [onOpen] is set; [action] is its own button beside the text.
 */
@Composable
fun PowerCard(
    icon: ImageVector,
    title: String,
    detail: String,
    state: StateLine,
    action: Pair<String, () -> Unit>? = null,
    onOpen: (() -> Unit)? = null,
) {
    Card(onClick = onOpen, clickLabel = onOpen?.let { "Open $title" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Midnight.Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Midnight.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Midnight.TextDim, fontSize = 14.sp, lineHeight = 19.sp)
                Text(state.text, color = state.tone.color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (action != null) {
                Spacer(Modifier.width(10.dp))
                AccentButton(action.first, onClick = action.second)
            } else if (onOpen != null) {
                Icon(HelperIcons.Chevron, contentDescription = null, tint = Midnight.TextDim, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun AccentButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Midnight.Accent,
            contentColor = Midnight.OnAccent,
            disabledContainerColor = Midnight.CardRaised,
            disabledContentColor = Midnight.TextDim,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.heightIn(min = 48.dp),
    ) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

@Composable
fun QuietButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Midnight.Text, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, Midnight.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.heightIn(min = 48.dp),
    ) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

/** A page's title row: a back control when the page was opened from another, and an optional line under the title. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(HelperIcons.Back, contentDescription = "Back", tint = Midnight.Text)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Midnight.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, color = Midnight.TextDim, fontSize = 14.sp)
        }
        trailing()
    }
}
