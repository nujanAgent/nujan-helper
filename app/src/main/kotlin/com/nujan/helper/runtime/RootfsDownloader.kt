package com.nujan.helper.runtime

import com.nujan.helper.core.runtime.RootfsSource
import com.nujan.helper.core.runtime.RuntimeLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Resumable download of the pinned rootfs. Streams to a `.part` file and, if
 * interrupted, resumes with an HTTP `Range` request so a dropped download is not restarted from zero. Tries each source
 * in turn; they are copies of the same bytes, so a `.part` from one resumes from the next. Verification is a separate
 * step (the caller hashes the file before extracting).
 */
class RootfsDownloader {

    /** Emits (bytesDownloaded, totalBytes-or-null) as the download progresses. */
    fun interface Progress {
        fun onProgress(downloaded: Long, total: Long?)
    }

    /**
     * Download the layer from the first of [sources] that serves it into [layout.rootfsArchive], resuming
     * [layout.rootfsArchivePart] if present. Honours coroutine cancellation between chunks (leaving a resumable `.part`).
     * Throws with every source's failure when none serves it, so the caller reports an honest failure.
     */
    suspend fun download(
        layout: RuntimeLayout,
        sources: List<RootfsSource>,
        progress: Progress,
    ): File = withContext(Dispatchers.IO) {
        File(layout.downloadDir).mkdirs()
        val finished = File(layout.rootfsArchive)
        if (finished.exists()) return@withContext finished // already downloaded; verify decides reuse.
        val failures = ArrayList<String>()
        for (source in sources) {
            try {
                fetch(layout, source, progress)
                return@withContext finished
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failures += "${describe(source)}: ${e.message ?: e.javaClass.simpleName}"
            } catch (e: IllegalStateException) {
                failures += "${describe(source)}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        error(failures.joinToString("; ").ifEmpty { "no source to download from" })
    }

    private suspend fun fetch(layout: RuntimeLayout, source: RootfsSource, progress: Progress) {
        val (url, headers) = when (source) {
            is RootfsSource.Url -> source.url to emptyMap()
            is RootfsSource.DockerHubBlob -> {
                val token = RootfsSource.DockerHubBlob.tokenFrom(readText(source.tokenUrl))
                    ?: error("the registry gave no pull token")
                source.blobUrl to mapOf("Authorization" to "Bearer $token")
            }
        }
        val part = File(layout.rootfsArchivePart)
        var have = if (part.exists()) part.length() else 0L

        val conn = openFollowingRedirects(url, rangeFrom = have, firstHopHeaders = headers)
        try {
            val code = conn.responseCode
            // 206 = server honoured the Range (resume). 200 = full body (server ignored Range or
            // we started at 0) — if we asked to resume but got 200, the bytes we have are stale.
            if (code == HttpURLConnection.HTTP_OK && have > 0) {
                part.delete(); have = 0
            } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                error("HTTP $code")
            }
            val remaining = conn.contentLengthLong.takeIf { it >= 0 }
            val total = remaining?.let { have + it }

            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, /* append = */ have > 0).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = input.read(buf)
                    progress.onProgress(have, total)
                    while (read >= 0) {
                        coroutineContext.ensureActive() // cancellation leaves a resumable .part.
                        output.write(buf, 0, read)
                        have += read
                        progress.onProgress(have, total)
                        read = input.read(buf)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val finished = File(layout.rootfsArchive)
        if (!part.renameTo(finished)) {
            part.copyTo(finished, overwrite = true); part.delete()
        }
    }

    private fun describe(source: RootfsSource): String = when (source) {
        is RootfsSource.Url -> URL(source.url).host
        is RootfsSource.DockerHubBlob -> "Docker Hub"
    }

    private fun readText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "nujan-helper")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${conn.responseCode} for the pull token")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Open [url] following redirects manually — [HttpURLConnection] drops the `Range` header (and refuses https->http)
     * across its automatic redirects, so we chase them ourselves and re-send it. [firstHopHeaders] go to [url] only: a
     * registry's token is not for the CDN it redirects to.
     */
    private suspend fun openFollowingRedirects(
        url: String,
        rangeFrom: Long,
        firstHopHeaders: Map<String, String>,
    ): HttpURLConnection {
        var current = url
        repeat(5) { hop ->
            coroutineContext.ensureActive()
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
                setRequestProperty("User-Agent", "nujan-helper")
                if (hop == 0) firstHopHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            when (conn.responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307, 308 -> {
                    val loc = conn.getHeaderField("Location") ?: error("redirect without Location")
                    conn.disconnect()
                    current = URL(URL(current), loc).toString()
                }
                else -> return conn
            }
        }
        error("too many redirects")
    }
}
