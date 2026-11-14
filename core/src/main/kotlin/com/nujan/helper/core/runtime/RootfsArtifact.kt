package com.nujan.helper.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Linux rootfs a fresh install bootstraps (downloaded on first run, never bundled). Debian 13.7
 * "trixie" for arm64, from Debian's reproducible build for Docker's official image (debuerreotype, serial 20261020):
 * the arm64 layer of `debian:trixie-20261020`. [SHA256] decides, so the bytes may come from any of [SOURCES]; a verified
 * copy is kept on the PC (`native/rootfs/DEBIAN_ROOTFS.md`). An install bootstrapped earlier keeps the rootfs it has.
 * Kept in `:core` so the pins are asserted by a JVM test and read identically by the downloader and the screens.
 */
object RootfsArtifact {
    const val LABEL = "Debian 13.7 (trixie) arm64 · minbase"

    /** SHA-256 of the compressed layer, Docker's layer digest. */
    const val SHA256 = "229b15d702220d963eaa48bcd8eab0ee7aa8f52996309c59cf44fce7deec3869"

    /** Compressed size in bytes, for the download estimate before it starts. */
    const val COMPRESSED_BYTES = 49_761_602L

    /** The tar inside it (Docker's diff_id `c70a535b…`), for the storage estimate; extracted it takes about 149 MB. */
    const val UNCOMPRESSED_BYTES = 145_971_200L

    /** What the unpacked rootfs takes on the phone: a little more than the tar, since its files fill whole blocks. */
    const val EXTRACTED_BYTES = 150L * 1024 * 1024

    val COMPRESSION = Compression.GZIP

    /** Where the layer is fetched from, in order; every copy is checked against [SHA256], whichever it came from. */
    val SOURCES: List<RootfsSource> = listOf(
        RootfsSource.DockerHubBlob(repository = "library/debian", digest = "sha256:$SHA256"),
        RootfsSource.Url(
            "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/" +
                "9c4818e3bfd35139d3798b0420e9c46836658b2e/trixie/oci/blobs/rootfs.tar.gz",
        ),
    )
}

enum class Compression { GZIP, XZ }

/** One place the rootfs layer can be fetched from. */
sealed interface RootfsSource {
    /** A plain download address. */
    data class Url(val url: String) : RootfsSource

    /**
     * A blob of a Docker Hub repository, addressed by its digest. The registry asks for an anonymous pull token first
     * ([tokenUrl]); the blob request ([blobUrl]) carries it and redirects to a CDN address that needs none.
     */
    data class DockerHubBlob(val repository: String, val digest: String) : RootfsSource {
        val tokenUrl: String get() = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$repository:pull"
        val blobUrl: String get() = "https://registry-1.docker.io/v2/$repository/blobs/$digest"

        companion object {
            /** The token in the auth service's answer, or null when there is none. */
            fun tokenFrom(answer: String): String? = try {
                val obj = Json.parseToJsonElement(answer).jsonObject
                (obj["token"] ?: obj["access_token"])?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
