package com.nujan.helper.export

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens Android's share sheet for a workspace zip in `exports/`, then closes. It is the Helper's own screen, so a call
 * can bring it up through the connected app the person is looking at (SPEC.md 9.1); the share sheet started from the
 * background would be blocked.
 */
class ShareExportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_FILE)?.takeIf { it.isNotBlank() && '/' !in it && it != "." && it != ".." }
        val file = name?.let { File(File(filesDir, EXPORTS), it) }?.takeIf { it.isFile }
        if (file != null) {
            val uri = FileProvider.getUriForFile(this, "$packageName.apkprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, TITLE))
        }
        finish()
    }

    companion object {
        const val EXTRA_FILE = "com.nujan.helper.extra.EXPORT_FILE"
        private const val EXPORTS = "exports"
        private const val TITLE = "Export workspace"
    }
}
