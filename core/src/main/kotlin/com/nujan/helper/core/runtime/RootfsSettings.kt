package com.nujan.helper.core.runtime

import java.io.File

/**
 * Settings the Helper keeps in the rootfs, applied before each launch so a rootfs bootstrapped earlier gets them too.
 * apt removes each package it downloads once the package is installed, whoever runs apt: the phone's storage is
 * scarce, and apt fetches a package again when it needs it.
 */
object RootfsSettings {
    /** Where the apt setting lives, relative to the rootfs. */
    const val APT_PATH = "etc/apt/apt.conf.d/90nujan-helper"

    const val APT_TEXT = "APT::Keep-Downloaded-Packages \"false\";\n"

    /** Writes the settings into the rootfs at [rootfsDir]; a rootfs not yet extracted, with no apt, is left alone. */
    fun apply(rootfsDir: File) {
        if (File(rootfsDir, "etc/apt/apt.conf.d").isDirectory) writeIfChanged(File(rootfsDir, APT_PATH), APT_TEXT)
    }
}

/**
 * Writes [text] to [file] unless it already holds exactly that. The file is replaced whole, so a program starting
 * meanwhile never reads half of it.
 */
fun writeIfChanged(file: File, text: String) {
    if (file.isFile && file.readText() == text) return
    file.parentFile?.mkdirs()
    val part = File(file.path + ".part")
    part.writeText(text)
    if (!part.renameTo(file)) {
        file.delete()
        part.renameTo(file)
    }
}
