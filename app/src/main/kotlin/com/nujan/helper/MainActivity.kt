package com.nujan.helper

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nujan.helper.ui.HelperApp
import com.nujan.helper.ui.HelperTheme

/** The Helper's one activity; its pages are in [HelperApp]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HelperTheme { HelperApp() }
        }
    }

    companion object {
        /** What tapping one of the Helper's notifications opens: the Helper's screen brought forward, never a second copy. */
        fun openFromNotification(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
