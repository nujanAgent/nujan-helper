# Releasing NuJan Helper

Releases are published on GitHub, where people and Obtainium find them. Each one is an APK signed with the Helper's
release key, its checksums, and the source of the GPL and LGPL programs the APK carries.

## Once: the release key

The key signs every release for as long as the Helper exists: Android installs an update only when it is signed by
the same key. Keep it out of the repository, and keep a copy off this computer.

```
keytool -genkeypair -v -keystore nujan-helper-release.jks -alias nujan-helper -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=NuJan Helper, O=NuJan"
```

The same key signs the Hands add-on, which the build carries inside the Helper: the add-on answers only an app with its own signature.

Then write `keystore.properties` at the repository root (git ignores it):

```
storeFile=<full path to nujan-helper-release.jks>
storePassword=<the store password>
keyAlias=nujan-helper
keyPassword=<the key password>
```

Without that file the release APK is built unsigned.

## Each release

1. Set `versionCode` (one higher) and `versionName` in `app/build.gradle.kts`, and add the release to `CHANGELOG.md`.
2. Build and check the release:

   ```
   ./gradlew :app:testDebugUnitTest :app:assembleRelease
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   sha256sum app/build/outputs/apk/release/app-release.apk
   ```

3. Tag the commit (`v<versionName>`) and create a GitHub release from the tag with:
   - the APK, named `nujan-helper-<versionName>.apk`;
   - `SHA256SUMS`, with the APK's digest, and the signing certificate's SHA-256 fingerprint in the release notes;
   - the corresponding source of the programs the APK carries (GPL-2.0-or-later and LGPL-3.0-or-later require it
     beside the binary): PRoot at tag `v5.1.107.101`, `talloc-2.5.0.tar.gz`, libandroid-shmem at tag `v0.7`, and
     Termux's recipes for the three (`packages/proot`, `packages/libtalloc`, `packages/libandroid-shmem`). Their
     versions and digests are in [`native/proot/PROVENANCE.md`](native/proot/PROVENANCE.md); when PRoot is updated,
     update both files and these archives together.
4. The release notes say what changed, in plain words, from `CHANGELOG.md`.

Obtainium follows the repository's releases by itself.
