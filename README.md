# VVVdroid

Read-only Android viewer for VVV 2.0 catalogs (`.vvv`, Firebird 5 ODS 13),
companion to the desktop app
[eVVVolved](https://github.com/Bro-Account/eVVVolved).

- Opens the catalog files in place, wherever they are stored (e.g. in a
  synced folder) — multi-GB catalogs are never copied into app storage
  unless you ask for a copy.
- **Read-only guarantee:** Firebird has no per-connection read-only attach —
  even a read-only transaction lets the engine update the header page and the
  transaction inventory on attach. A database is provably immutable only when
  its header page carries the persistent read-only flag (`hdr_read_only`),
  which is the storage format of VVV 2.0. VVVdroid reads that flag straight
  from the file *before* attaching (page 0, offset 42, bit `0x20`), opens
  flagged catalogs in place, and never modifies them (measured: identical
  MD5 and mtime after open + queries; writes are refused by the engine).
- **Read-write catalogs** (older conversions, or a catalog left unflagged)
  are not opened in place by default: a dialog offers a private copy in app
  storage (original untouched) or an explicit "open in place anyway".
  Private copies live only for the session: closing the catalog deletes the
  copy, and an unclean exit is cleaned up on the next start.
- Browses one hierarchy level per screen (volumes → folders → files, like
  KeePassDroid): no desktop-style tree, system back returns one level.
- Uses the official Firebird 5 Android build
  (`Firebird-5.0.4.1812-0-android-embedded.aar` from the FirebirdSQL
  releases) via a thin JNI bridge.

## Status

Working prototype: open a catalog (in place or as a private copy), list
volumes, browse folders and files, show file info. Planned next: search by
name/extension over the whole catalog.

Folder listings come from the `PATHS` tree (`VOLUME_ID` + `FATHER_ID`) and
the files attached to that path (`FILES.PATH_ID`); a folder's own `FILES`
row (`PATH_FILE_ID` points to its `PATHS` entry) is excluded, so a folder
never shows up twice.

## Building

1. Provision the Firebird embedded AAR (downloads from the official
   FirebirdSQL release into `app/libs/`, not stored in git):

   ```powershell
   .\scripts\fetch-firebird-android.ps1
   ```

2. Open the project in Android Studio, or build from the command line:

   ```powershell
   .\gradlew :app:assembleDebug
   ```

JDK 17+, Android SDK with NDK (the JNI bridge compiles C/C++ against the
fbclient API), minSdk 26.

## Testing

The app accepts launch extras, which makes device/emulator cycles scriptable:

```powershell
adb shell am start -n com.vvvdroid.app/.MainActivity `
    -e vvv.catalog /sdcard/Download/_AndroidTest_ro.vvv `
    -e vvv.mode auto      # auto | copy | inplace
```

`auto` asks when the catalog is read-write, `copy` always opens a private
copy, `inplace` opens the file where it is. `scripts/emulator-test.ps1`
builds, installs, pushes test catalogs and dumps the UI text — it needs an
AVD plus roughly 12 GB free disk space and 8 GB RAM (the API 37 image is
large).

## Licenses

Firebird is bundled under the InterBase Public License / Initial
Developer's Public License. The full texts ship inside the APK and in this
repository at `app/src/main/assets/licenses/` (`IDPLicense.txt`,
`IPLicense.txt`).
