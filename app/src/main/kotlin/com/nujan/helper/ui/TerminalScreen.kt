package com.nujan.helper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nujan.helper.di.ServiceLocator

/**
 * A login shell in the toolbox on a pseudo-terminal: the screen as the shell draws it, a typed line sent on Send, and
 * Ctrl-C. It is line-based, so full-screen programs such as vim or htop do not draw here.
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val controller = remember { ServiceLocator.newTerminalController() }
    DisposableEffect(Unit) {
        controller.start()
        onDispose { controller.close() }
    }
    val screenText by controller.screen.collectAsState()
    val alive by controller.alive.collectAsState()
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(screenText) { scroll.scrollTo(scroll.maxValue) }
    val send = {
        controller.send(input + "\n")
        input = ""
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Terminal", subtitle = if (alive) "Shell running" else "Shell ended", onBack = onBack) {
            QuietButton("Ctrl-C", { controller.sendCtrlC() }, enabled = alive)
        }
        Surface(
            color = Midnight.Card,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(Modifier.verticalScroll(scroll).padding(12.dp)) {
                Text(screenText, color = Midnight.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text("Type a command", color = Midnight.TextDim) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = Midnight.Text, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Midnight.Accent,
                    unfocusedBorderColor = Midnight.Line,
                    cursorColor = Midnight.Accent,
                ),
                modifier = Modifier.weight(1f),
            )
            AccentButton("Send", send, enabled = alive)
        }
    }
}
