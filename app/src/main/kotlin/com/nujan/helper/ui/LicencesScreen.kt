package com.nujan.helper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Helper's notice, the software it carries with where its source is, and each licence's full text on a tap. */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val texts by produceState<LicenceTexts?>(null) {
        value = withContext(Dispatchers.IO) { LicenceTexts.read(context.assets) }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Licences", subtitle = "The Helper and the software it carries", onBack = onBack)
        val loaded = texts ?: return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Card { Text(loaded.notice, color = Midnight.Text, fontSize = 15.sp) }
            SectionLabel("Software in the Helper")
            Card { Text(loaded.thirdParty, color = Midnight.Text, fontSize = 13.sp, lineHeight = 19.sp) }
            SectionLabel("Licence texts")
            Column { loaded.licences.forEach { (name, text) -> LicenceCard(name, text) } }
        }
    }
}

/** One licence by name; a tap shows or hides its full text. */
@Composable
private fun LicenceCard(name: String, text: String) {
    var open by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.padding(bottom = 10.dp),
        onClick = { open = !open },
        clickLabel = if (open) "Hide the text of $name" else "Show the text of $name",
    ) {
        Text(name, color = Midnight.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (open) Text(text, color = Midnight.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
    }
}
