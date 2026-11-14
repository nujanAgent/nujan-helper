package com.nujan.helper.runtime

import android.content.Context
import com.nujan.helper.core.runtime.RuntimeLayout
import java.io.File
import java.io.IOException

/**
 * Unpacks the bundled proot runtime (APK assets under `proot-runtime/`, populated by the Gradle
 * copyProotRuntime task) into the app's private files dir and marks the binaries executable.
 *
 * Executing from app-private storage is legal because the app ships targetSdk 28 —
 * the `untrusted_app_27` SELinux domain permits exec of `app_data_file`.
 */
class AssetUnpacker(private val context: Context) {

    private val assetRoot = "proot-runtime"

    /** Copy assets → bootstrap dir, set exec bits, create runtime dirs. Idempotent. */
    fun unpack(layout: RuntimeLayout) {
        copyAssetDir(assetRoot, layout.bootstrapDir)
        layout.runtimeDirs.forEach { File(it).mkdirs() }
        layout.executables.forEach { File(it).setExecutable(true, /* ownerOnly = */ false) }
        // Support libs must be readable by the (system) linker; app-private already is, but be explicit.
        File(layout.libDir).listFiles()?.forEach { it.setReadable(true, false) }
    }

    /** True once the proot binary is present and executable. */
    fun isUnpacked(layout: RuntimeLayout): Boolean =
        File(layout.prootBin).let { it.exists() && it.canExecute() }

    private fun copyAssetDir(assetPath: String, destDir: String) {
        val am = context.assets
        val children = am.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            copyIfChanged(assetPath, File(destDir))
            return
        }
        File(destDir).mkdirs()
        for (child in children) copyAssetDir("$assetPath/$child", "$destDir/$child")
    }

    /**
     * Writes the asset only when [dest] differs, through a staging file renamed into place: a binary that is running,
     * such as proot under a job or the setup, cannot be opened for writing (ETXTBSY), but it can be replaced.
     */
    private fun copyIfChanged(assetPath: String, dest: File) {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        if (dest.isFile && dest.length() == bytes.size.toLong() && dest.readBytes().contentEquals(bytes)) return
        dest.parentFile?.mkdirs()
        val staging = File(dest.parentFile, ".${dest.name}.new")
        staging.writeBytes(bytes)
        if (!staging.renameTo(dest)) {
            staging.delete()
            throw IOException("could not put ${dest.name} in place")
        }
    }
}
