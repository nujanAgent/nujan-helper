# Third-party software in NuJan Helper

The Helper's APK carries the components below. Each keeps its own licence; the full licence texts are in
[`LICENSES/`](LICENSES/). The Helper's own code is under the licence in [`LICENSE`](LICENSE).

## Programs the Helper runs as separate processes

These are unmodified arm64 binaries from the Termux package repository. The Helper starts them as programs of their
own and does not link them into its code.

| Component | Version | Files in the APK | Licence | Source |
| --- | --- | --- | --- | --- |
| PRoot | 5.1.107.101 | `proot`, `loader`, `loader32` | GPL-2.0-or-later ([text](LICENSES/GPL-2.0.txt)) | https://github.com/termux/proot, tag `v5.1.107.101` (commit `b27d81a68d76`) |
| talloc | 2.5.0 | `libtalloc.so.2` | LGPL-3.0-or-later ([text](LICENSES/LGPL-3.0.txt), with [GPL-3.0](LICENSES/GPL-3.0.txt)) | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz |
| libandroid-shmem | 0.7 | `libandroid-shmem.so` | BSD-3-Clause ([text](LICENSES/BSD-3-Clause-libandroid-shmem.txt)) | https://github.com/termux/libandroid-shmem, tag `v0.7` |

They are built by Termux's recipes in https://github.com/termux/termux-packages (`packages/proot`,
`packages/libtalloc`, `packages/libandroid-shmem`; the PRoot recipe at commit `d6a36549`). The packages they come
from, with their SHA-256 digests, are listed in [`native/proot/PROVENANCE.md`](native/proot/PROVENANCE.md).

**Source code.** Every release of the Helper on GitHub carries, beside the APK, the complete source of the PRoot,
talloc and libandroid-shmem versions it ships, with the recipes that build them. If a release lacks them, open an
issue and they will be supplied for three years from that release.

## Libraries compiled into the app

| Component | Version | Licence |
| --- | --- | --- |
| ATP wire module and provider kit, Copyright 2026 NuJan | drop `atp-v0.24` (`third_party/atp`) | Apache-2.0 ([text](LICENSES/Apache-2.0.txt)) |
| usb-serial-for-android | 3.11.0 (`third_party/usb-serial-for-android`) | MIT ([text](third_party/usb-serial-for-android/LICENSE.txt)) |
| XZ for Java | 1.10 | 0BSD |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | 2.4.0, 1.11.0, 1.11.0 | Apache-2.0 |
| AndroidX (Core, Activity, Lifecycle, Compose UI, Material 3) | see `gradle/libs.versions.toml` | Apache-2.0 |

## Downloaded when the person asks for them

These are not in the APK. The Helper fetches them from their publishers when they are set up, and each comes with its
own licences and source:

- **Debian**, the Linux system: the official `debian` image on Docker Hub (built by
  https://github.com/debuerreotype/docker-debian-artifacts). Debian's sources are at https://sources.debian.org and,
  for any past version, https://snapshot.debian.org.
- **Packages** installed with `apt` come from `deb.debian.org` under their own licences.
- **Node.js**, for the web profile, from https://nodejs.org.

NuJan Helper is not affiliated with Termux, Debian, Docker or the Godot Foundation.
