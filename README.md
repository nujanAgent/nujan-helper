# NuJan Helper

NuJan Helper gives the NuJan agent app a computer on your phone: a Debian Linux system, workspaces for projects,
commands and long-running jobs, development servers, MCP servers, and USB serial boards. NuJan reaches it through
ATP (App Tools Protocol), only after you allow it on the Helper's own screen, and only for the groups of tools you
turn on. Any other app that speaks ATP can ask in the same way.

The Helper is open source and is distributed here, on GitHub, not on Google Play.

## What it can do for an agent

- Run commands in Debian, with output while they run, input when one asks a question, and a limit on how long and how
  much each may use
- Keep a project in a workspace: read, write, edit, search and move its files, and export it as a zip
- Install toolchains (web, Python, native builds, Android builds) and the packages a project needs
- Start development servers and MCP servers, and keep them running in the background
- Talk to boards over USB serial, such as an ESP32 or ESP8266 running MicroPython
- Drive this phone, if you turn it on: see the screen, act in other apps and use Android's shell, through Wireless debugging or a small
  Accessibility add-on, for testing and development. **At your own risk.** See [TERMS.md](TERMS.md)
- Build an APK and hand it to Android's install screen, where you decide

Every capability has a switch on the Helper's screen, and every connected app has its own grants under Connected apps.

## Requirements

- An arm64 Android phone with Android 8.0 or later
- About 300 MB free for the Linux system and its basics, more for toolchains
- NuJan, or another app that speaks ATP

## Install

1. Download the APK from the latest [release](https://github.com/nujanAgent/nujan-helper/releases).
2. Check it: each release lists the APK's SHA-256 and the signing certificate's fingerprint.
3. Open it on the phone and allow the install.

[Obtainium](https://github.com/ImranR98/Obtainium) can follow this repository's releases and keep the Helper up to
date: add `https://github.com/nujanAgent/nujan-helper` as an app.

The first start sets up the Linux system, which downloads Debian (see [PRIVACY.md](PRIVACY.md)).

## Build from source

You need JDK 17 and the Android SDK (Android Studio installs both).

```
./gradlew :app:assembleRelease
```

The APK is in `app/build/outputs/apk/release/`. It is signed with the key that `keystore.properties` names, and is
unsigned without one; [RELEASING.md](RELEASING.md) says how to make both. The PRoot binaries the Helper runs are
prebuilt from Termux's packages and kept in `native/proot/`; where they come from, with their checksums, is in
[`native/proot/PROVENANCE.md`](native/proot/PROVENANCE.md).

## Documents

- [PRIVACY.md](PRIVACY.md): what stays on the phone and what leaves it
- [SECURITY.md](SECURITY.md): how to report a security problem
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md): the software the Helper carries, its licences and its source
- [TERMS.md](TERMS.md): using the Helper

## Licence

The Helper's own code is under the licence in [LICENSE](LICENSE). The software it carries keeps its own licences,
listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

NuJan Helper is not affiliated with Termux, Debian, Docker or the Godot Foundation.
