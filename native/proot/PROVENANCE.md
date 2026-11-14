# proot prebuilts: provenance, checksums, and license (updated 2026-10-25)

These arm64-v8a binaries are **vendored prebuilts** used by the Helper as **separately spawned
binaries** (never linked into the Helper's process). They come from the official Termux package
repository and are used unmodified. Updated on 2026-10-25 from 5.1.107.86 to **5.1.107.101**.

## Contents (`arm64-v8a/`)

| File | Role | sha256 |
|---|---|---|
| `bin/proot` | PRoot 5.1.107.101 userspace chroot/bind engine | `bc3e5416748276374c513eaad1c6cabedc94246fdf2dd58fa6531374e481fa09` |
| `libexec/proot/loader` | PRoot 64-bit loader (static, no deps) | `1da54355333cca3fb6ee10e3b82a24df24fb230c99241ce83809bf9e240609b5` |
| `libexec/proot/loader32` | PRoot 32-bit loader | `c429996fee730e1a4fe12625b22dab5a5bc2847a4cae274d36fd4a19cd8299a8` |
| `lib/libtalloc.so.2` | talloc 2.5.0 (proot dependency; SONAME unchanged) | `742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309` |
| `lib/libandroid-shmem.so` | System V shm shim 0.7 (proot dependency on bionic; unchanged) | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` |

`bin/proot` dynamic deps (DT_NEEDED): `libtalloc.so.2`, `libandroid-shmem.so`, `libc.so`. Its ELF
interpreter is `/system/bin/linker64` (the **system** linker — runs in any app process, not tied to
the Termux prefix). `libc.so` resolves to the device's system bionic libc. The two bundled `.so`
files are supplied via `LD_LIBRARY_PATH`. The 64-bit ELF objects have LOAD `p_align=0x4000`
(**16 KB-page ready**); `loader32` is `0x1000`, as before.

## Why 5.1.107.101

glibc 2.39 and later (Debian 13 has 2.41) change a mode without following symlinks through the
`fchmodat2` system call. proot 5.1.107.86 and the 5.1.107.96 tag do not translate that call's path,
so where Android's app seccomp filter lets it through (Android 16 QPR1 and later) it fails or acts on
a host path. termux/proot fixed it in `7fca80c8a70b` ("syscall: translate fchmodat2", 2026-10-12);
the 5.1.107.101 tag is commit `b27d81a68d76`, 64 commits ahead of that fix and behind none
(GitHub compare). Termux's `packages/proot/build.sh` builds that tag (`TERMUX_PKG_SHA256`
`a66743f50c7b0bac1913b83bf55e139d679c80a0ca2808eec76432a65640017f`, termux-packages `d6a36549`).

Checked on an Android 16 phone (security patch 2026-09-01), in throwaway directories:

| | `chmod` | `chmod -h` | `cp -a` | `tar x` | `yes \| head -1` |
|---|---|---|---|---|---|
| Debian 12.15, proot .101 | 700 | (Debian 12's coreutils has no `-h`) | 700 | 700 | ok |
| Debian 12.15, proot .86 | 700 | (same) | 700 | 700 | ok |
| Debian 13.7, proot .101 | 700 | ok | 700 | 700 | ok |
| Debian 13.7, proot .86 | 700 | "No such file or directory" | 700 | 700 | ok |

## Upstream source packages (the corresponding-source offer, GPL)

Downloaded from `https://packages.termux.dev/apt/termux-main/pool/main/` (each matches the Packages
index of `dists/stable`):

| Package | .deb | sha256 | License |
|---|---|---|---|
| proot 5.1.107.101 | `p/proot/proot_5.1.107.101_aarch64.deb` | `a28355e9be257e1f87f4e9294b3118470d4fd7a5442fed8b278f4443c83407d7` | **GPL-2.0** |
| libtalloc 2.5.0 | `libt/libtalloc/libtalloc_2.5.0_aarch64.deb` | `556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7` | **LGPL-3.0-or-later** (talloc's own `talloc.h`; Termux's package metadata says GPL-3.0) |
| libandroid-shmem 0.7 | `liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb` | `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6` | **BSD-3-Clause** (its `LICENSE`; Termux's metadata agrees) |

**Corresponding source:** PRoot — https://github.com/termux/proot at tag `v5.1.107.101` (Termux
packaging: https://github.com/termux/termux-packages/tree/master/packages/proot). talloc —
https://gitlab.com/samba-team/samba (Termux packaging: `.../packages/libtalloc`). These upstreams,
pinned to the versions above, are the complete corresponding source for the vendored binaries. The
Helper's Licences page carries this offer and the attribution, and each release attaches the source
archives (`RELEASING.md`).

## Why these are not built from source

The Helper uses Termux's builds as they are, from the public recipes named above. Building PRoot and
talloc from source with the NDK is a possible later step; the prebuilts already have 16 KB alignment.
