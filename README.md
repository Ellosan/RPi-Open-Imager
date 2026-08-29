# RPi Open Imager

A Raspberry Pi Imager for Android. Pick an operating system from the official Raspberry Pi
catalogue, pick an SD card, and the phone downloads, decompresses, writes and verifies the image —
then applies the same first boot settings the desktop tool does.

No root is needed for the usual case: an SD card in a USB card reader is written by talking SCSI
over USB directly, which is the only route Android leaves open to an ordinary app.

## What it does

- **Live OS catalogue.** The same `os_list_imagingutility_v4.json` Raspberry Pi Imager uses, with
  nested categories, per board filtering (`Raspberry Pi 5`, `Pi Zero 2 W`, …) and a cached copy so
  the picker still opens offline.
- **Streaming writes.** Download, decompress, hash and write happen in one pass, so a 6 GB image
  never lands on the phone's storage. `.img`, `.img.xz`, `.img.gz`, `.img.bz2` and `.zip` are
  detected from their magic bytes, not their file name.
- **Verification.** The card is read back and compared with the SHA-256 of what was written, and
  with the catalogue's published checksum when there is one.
- **OS customisation.** Hostname, user account, Wi-Fi, locale, keyboard and SSH are written into the
  boot partition after the image, through `firstrun.sh` or cloud-init depending on what the image
  declares. Passwords are turned into a SHA-512 crypt hash and the Wi-Fi passphrase into a WPA PSK
  before they are stored or written — neither ever reaches the card in the clear.
- **Custom images.** Any image on the phone can be written, with the init format detected from the
  boot partition the same way the desktop tool guesses it.
- **Background writes.** A foreground service with a progress notification and a cancel action keeps
  going with the screen off.

## Two ways to write an SD card

| Route | Needs root | How it works |
| --- | --- | --- |
| **USB card reader or USB drive** (recommended) | No | The card reader is claimed through Android's USB host API and driven with the USB Bulk Only Transport: SCSI `READ CAPACITY`, `READ(10)/(16)` and `WRITE(10)/(16)` command blocks over the bulk endpoints. Multi slot readers are handled by probing each LUN for a card. |
| **The phone's own SD card slot** | Yes | Android exposes a built-in card only as a mounted filesystem, never as a raw device, so there is no unrooted route. With root turned on in the storage picker, removable `/dev/block` nodes are listed, unmounted and streamed through `dd`. |

Everything on the chosen card is erased, so the picker only ever lists removable devices and the
write is behind a confirmation naming the card and its capacity.

## Building

Needs JDK 17+ and the Android SDK (compile SDK 35).

```sh
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test             # the engine's test suite
./gradlew :app:lintDebug
```

`local.properties` needs `sdk.dir=/path/to/android-sdk`, or set `ANDROID_HOME`.

## Installing

The debug build is signed with the standard Android debug key, so it installs as it is:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and open it, allowing installs from that source. It uses the
application id `dev.openimager.debug`, so it sits happily next to a release build.

`./gradlew :app:assembleRelease` produces a minified but unsigned APK — sign that with your own key
before installing it.

Android 8.0 (API 26) or newer, and a phone that supports USB OTG for the card reader route.

## Layout

```
core/   Plain JVM library: block devices, MBR, FAT, the write pipeline, customisation, catalogue
app/    Android: USB and root block devices, the write service, and the Compose UI
```

Keeping the engine out of the Android module is what makes it testable. `core` has no Android
imports, so the FAT writer, the crypt implementation and the whole download → write → verify →
customise pipeline run on a plain JVM.

### Notable pieces

- `core/fat/FatFileSystem.kt` — a small FAT12/16/32 reader and writer for the boot partition's root
  directory. Long file names are written where needed, but a name that fits 8.3 keeps its canonical
  short entry: a `CMDLIN~1.TXT` would leave the card unbootable, because the Pi firmware looks the
  file up through the short name.
- `core/image/ImageWriter.kt` — the single pass pipeline, with progress, cancellation, capacity
  checks and read back verification.
- `core/customization/` — `firstrun.sh` and cloud-init generation matching Raspberry Pi Imager's
  own output (including the `raspberrypi-sys-mods` and `userconf-pi` paths), SHA-512 crypt, and
  WPA PSK derivation.
- `core/scsi/` — the Bulk Only Transport wrappers and SCSI command blocks, kept away from the
  Android USB plumbing so they can be checked byte for byte.
- `app/usb/UsbBlockDevice.kt` — the mass storage driver on top of them: LUN probing, data phase,
  stall recovery and sense decoding, so a locked card reports "the card is write protected" rather
  than a numeric error.

## Testing

`./gradlew :core:test` runs 34 tests that check the risky parts against outside references rather
than against themselves:

- FAT volumes are created with `mkfs.vfat`, written by this code, then checked with `fsck.vfat` and
  read back with `mtools` — for FAT12, FAT16 and FAT32, including replacing an existing file and
  growing a full root directory.
- Password hashes are compared byte for byte with `openssl passwd -6` and with glibc's `crypt`.
- Wi-Fi PSKs are compared with the IEEE 802.11i test vectors.
- A Pi shaped disk image (MBR plus a FAT boot partition) is built, xz compressed, written through
  the real pipeline onto a file backed card, verified, customised, and the result inspected with
  `mtools`.
- The USB command and status wrappers and the SCSI command blocks are asserted byte for byte
  against the Bulk Only Transport layout, since a misplaced field there means sectors written to
  the wrong place.
- xz, gzip, bzip2, zip and raw images are round tripped through the decompressor.

The FAT and image tests skip themselves when `dosfstools` and `mtools` are not installed.

## Limitations

- zstd compressed images are not supported; the `.img.xz` or `.img.gz` build of the same OS is.
- Writing needs a USB OTG capable phone for the unrooted route. Android asks for permission per
  device, so the first use of each reader raises a dialog.
- Bootloader and EEPROM images in the catalogue are written like any other image; they are only
  useful on the boards they name.

## Licence

MIT. See [LICENSE](LICENSE).

Not affiliated with or endorsed by Raspberry Pi Ltd. The OS catalogue and the images it points at
are published by Raspberry Pi and the respective OS authors.
