# usb-serial-for-android, vendored

Do not edit these files. They are the library sources of usb-serial-for-android release `3.11.0` (commit
`8e694372dee4531d140766df7af3189d3d9531c5`, https://github.com/mik3y/usb-serial-for-android), under the MIT
License (`LICENSE.txt`). Upstream's `usbSerialForAndroid/` module folder maps to this folder: its
`proguard-rules.pro` and `src/main/` are here unchanged, and the repository's `LICENSE.txt` beside them.
`SHA256SUMS` lists every vendored file, and the Helper's checksum test fails on any edit.

`build.gradle.kts` and this README belong to the Helper and are not part of the drop. To update, fetch the same files
from a newer release tag and regenerate `SHA256SUMS`.

The drivers open a USB-serial bridge (CDC-ACM, CP210x, CH34x, FTDI, PL2303) through Android's USB host API once the
person has allowed the Helper to use the device.
