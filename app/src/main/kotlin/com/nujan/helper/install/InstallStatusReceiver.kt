package com.nujan.helper.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nujan.helper.di.ServiceLocator

/**
 * Receives Android's answer for each install the Helper committed. It is declared in the manifest, so Android starts
 * the Helper to deliver an answer that arrives after the process that asked has ended.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ServiceLocator.apkInstaller.onStatus(intent)
    }
}
