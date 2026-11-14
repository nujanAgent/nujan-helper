package com.nujan.helper.hands

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** How NuJan Helper reaches the service: each `call` is one thing to do. Only an app signed with this app's key is answered. */
class HandsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val packages = requireNotNull(context).packageManager
        if (packages.checkSignatures(Binder.getCallingUid(), Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("Only an app signed with NuJan Helper's key may use this.")
        }
        val context = requireNotNull(context)
        // These two answer whether or not the service is on: the Helper sets what it needs between drives and asks why it is off.
        when (method) {
            "configure" -> {
                HandsState.configure(context, extras?.getStringArrayList("guarded").orEmpty(), extras?.getBoolean("off_when_idle") == true)
                return Bundle()
            }
            "status" -> return Bundle().apply {
                putBoolean("running", HandsService.running != null)
                putBoolean("turned_itself_off", HandsState.turnedItselfOff(context))
                putString("stopped_for", HandsState.stoppedFor(context, extras?.getLong("since") ?: 0L))
            }
        }
        val service = HandsService.running ?: return Bundle()
        return Bundle().apply {
            // The service's calls wait on Android; they run with this caller's identity cleared, as the service's own.
            val token = Binder.clearCallingIdentity()
            try {
                when (method) {
                    "tree" -> putString("tree", service.tree())
                    "in_front" -> putString("package", service.inFront())
                    "keyboard_top" -> putInt("top", service.keyboardTop())
                    "screenshot" -> {
                        val (bytes, problem) = service.picture()
                        putByteArray("image", bytes)
                        putString("problem", problem)
                    }
                    "gesture" -> putBoolean(
                        "done",
                        extras != null && service.gesture(
                            extras.getInt("x").toFloat(),
                            extras.getInt("y").toFloat(),
                            extras.getInt("to_x").toFloat(),
                            extras.getInt("to_y").toFloat(),
                            extras.getLong("ms"),
                        ),
                    )
                    "global" -> putBoolean("done", service.global(extras?.getString("action").orEmpty()))
                    "set_text" -> putBoolean("done", service.setFocusedText(extras?.getString("text").orEmpty()))
                    "in_use" -> service.inUse(extras?.getString("app"))
                    "done" -> service.done()
                    "open" -> putBoolean("done", service.open(extras?.getString("uri").orEmpty()))
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
