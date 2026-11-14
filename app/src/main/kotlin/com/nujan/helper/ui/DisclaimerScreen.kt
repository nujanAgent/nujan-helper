package com.nujan.helper.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nujan.helper.HelperLinks
import com.nujan.helper.core.phone.Disclaimer

/** A warning to read and accept: nothing goes ahead until each box is ticked and the button pressed. */
@Composable
fun DisclaimerBody(disclaimer: Disclaimer, onAccept: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var ticked by rememberSaveable(disclaimer.id) { mutableStateOf(emptyList<Int>()) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(disclaimer.title, color = Midnight.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
        disclaimer.paragraphs.forEach { paragraph ->
            Text(paragraph, color = Midnight.Text, fontSize = 15.sp, lineHeight = 22.sp)
        }
        QuietButton("Read the terms", { openTerms(context) }, Modifier.fillMaxWidth())
        disclaimer.ticks.forEachIndexed { index, tick ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = index in ticked, role = Role.Checkbox, onValueChange = { on -> ticked = if (on) ticked + index else ticked - index }),
            ) {
                Checkbox(
                    checked = index in ticked,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = Midnight.Accent, uncheckedColor = Midnight.TextDim, checkmarkColor = Midnight.OnAccent),
                )
                Text(tick, color = Midnight.Text, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 12.dp))
            }
        }
        AccentButton(disclaimer.accept, onAccept, Modifier.fillMaxWidth(), enabled = ticked.size == disclaimer.ticks.size)
    }
}

private fun openTerms(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HelperLinks.TERMS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (none: ActivityNotFoundException) {
        // Nothing on the phone opens web pages; the warning above already says what the terms do.
    }
}

/** The Helper's first screen until its warning is accepted. */
@Composable
fun FirstRunDisclaimer(disclaimer: Disclaimer, onAccept: () -> Unit) {
    DisclaimerBody(disclaimer, onAccept, Modifier.fillMaxSize())
}
