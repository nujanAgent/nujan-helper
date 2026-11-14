# Debian rootfs: pinned source (Debian 13 since 2026-10-25)

The rootfs is **downloaded on first run, not bundled**. `RootfsArtifact` in `:core` is the pin the
code reads; this file is its record. Installs bootstrapped earlier keep the rootfs they have.

## Pinned artifact (2026-10-25)

- **Distro:** Debian 13.7 "trixie", arm64, minbase: Debian's reproducible build for Docker's official image
  (debuerreotype 0.17, serial `20261020`, snapshot.debian.org `20261020T213255Z`, epoch `1792531975`), the arm64 layer of
  `debian:trixie-20261020`. glibc 2.41.
- **sha256 (`rootfs.tar.gz`, Docker's layer digest):** `229b15d702220d963eaa48bcd8eab0ee7aa8f52996309c59cf44fce7deec3869`,
  49,761,602 bytes. Uncompressed tar: 145,971,200 bytes, sha256 `c70a535b93b34728442ddff3b1847c7232640c59412ecb84c3c8eedd2b7c4a7f`
  (the image config's `diff_id`).
- **Sources, in order** (the digest decides, so any copy works):
  1. Docker Hub, by digest: an anonymous pull token from `auth.docker.io`, then
     `registry-1.docker.io/v2/library/debian/blobs/sha256:229b…`, which redirects to Docker's CDN (checked 2026-10-25:
     307, and the CDN honours `Range`, `Content-Range: bytes 1000-1999/49761602`). Docker Hub keeps dated official
     tags, so the layer stays reachable.
  2. `debuerreotype/docker-debian-artifacts`, commit `9c4818e3bfd35139d3798b0420e9c46836658b2e`,
     `trixie/oci/blobs/rootfs.tar.gz`.
- **Why not linuxcontainers any more:** it keeps only its last three builds (checked 2026-10-25: `20261020`, `20261021`,
  `20261022`), which is why the July pin answers 404; its builds cannot be rebuilt.
- **Size on the phone:** 149 MB extracted; 231 MB with the basics.
- **License:** Debian is a composite of DFSG-free licenses; ship Debian's copyright pointer in About. Publishing a copy
  of the rootfs here would bring the duty to offer its source.

## What the bootstrap does with it

1. Downloads from the first source that answers, resumable; checks the SHA-256; decompresses gzip in the app.
2. Extracts **under proot** `-0 --link2symlink` with the phone's own `toybox tar` (hardlinks become `.l2s.*` symlinks;
   `-0` fakes root ownership).
3. Writes `/etc/hosts` (minbase has none; dev servers use `localhost`) and a real `/etc/resolv.conf`.
4. Smoke-boots it, then installs the basics: ca-certificates, curl, netbase, procps, python3. A bootstrap stopped
   part-way installs them on its next run; without them the toolbox still works and the profiles install what they need.

## Runtime facts (re-checked 2026-10-25)

1. **Extract under proot** `-0 --link2symlink`: Android SELinux blocks hardlinks in app storage.
2. **No on-device xz**: the app decompresses (gzip with `java.util.zip`, xz with `org.tukaani:xz`).
3. **proot 5.1.107.101 or later** for Debian 13 (glibc 2.39+ uses `fchmodat2`; see `native/proot/PROVENANCE.md`).
4. **apt runs as is under proot -0**: Debian 12's apt 2.6.1 and Debian 13's apt 3.0.3 install without
   `APT::Sandbox::User=root` (an Android 16 phone, 2026-10-25: the basics in 60 s, exit 0).
5. **Binds:** `-b /proc -b /dev -b /sys`; enter with `env -i HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8`.
6. **Seccomp:** no `PROOT_NO_SECCOMP=1` needed on the phones tested. Keep it as a fallback flag for kernels that
   reject proot's seccomp filter.
