# SlimPDF

A PDF reader for Android with no runtime dependencies at all — no AndroidX, no Compose,
no third-party PDF engine. The signed release APK is **79 KB**, and the whole app is 1,974 lines
of Kotlin compiling to 613 methods in a single dex.

|                      |                      |
| -------------------- | -------------------- |
| Release APK          | 79 KB (81,076 bytes) |
| Methods              | 613                  |
| Runtime dependencies | none                 |
| minSdk / targetSdk   | 29 / 37              |

## What it does

- Open a PDF from the system picker, or via **Open with** / **Share** from any other app.
- Continuous vertical scrolling with fling, pinch zoom and double-tap zoom.
- Recents list showing where you left off in each document; swipe a row aside to remove it,
  with an undo. A PDF opened from another app stays openable from there, and picks up
  where you left off even when it is shared again under a new link.
- Long-press a row to favourite it or remove it. Favourites are listed first, never fall off
  the end of the list, and cannot be swiped away by accident.
- Pages run edge to edge under the status bar, except that a document opened at the top
  starts its first page just below it.
- Resumes at the exact scroll position, and keeps it across rotation and process death.
- Follows the system light/dark setting.

## What it deliberately does not do

No text search, no text selection, no copy. `android.graphics.pdf.PdfRenderer` — the
platform's built-in engine — rasterises pages and exposes no text layer, so any of those
features would mean bundling PdfBox-Android or an NDK build of MuPDF/PDFium. That is
8–15 MB against a 79 KB app, which is the opposite of the point. If you need search, this
is the wrong reader.

Password-protected PDFs open on Android 15 and later, where `PdfRenderer` accepts a
password. On Android 10–14 they cannot be opened, for the same reason as above: the
platform engine there takes no password, and working around it means bundling one. The
password is asked for each time and never stored — keeping it safely would take
Keystore-backed storage, which a reader should not need.

## Building

Requires JDK 17+ and the Android SDK. Point Gradle at the SDK with either `ANDROID_HOME`
or a `local.properties` containing `sdk.dir=/path/to/Android/sdk` (git-ignored).

```sh
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleRelease      # minified, resource-shrunk release APK
./gradlew :app:connectedAndroidTest # instrumented tests, needs a device or emulator
```

### Signing

`assembleRelease` signs the APK when a `keystore.properties` exists at the repo root:

```properties
storeFile=local/slimpdf-release.jks
storePassword=…
keyAlias=slimpdf
keyPassword=…
```

Both that file and the keystore are git-ignored and must stay that way — losing them
means never being able to ship an update to an app published under that key. Without
them the release build still succeeds, it just comes out unsigned, so a fresh clone
builds without any setup.

Only the v3 signature scheme is applied. minSdk 29 is well past v1's API 24 cutoff and
v2's API 24–27 window, so those blocks would be dead weight; v3 is also what permits the
signing key to be rotated later without orphaning existing installs.

The key is RSA 4096 with a certificate running to 2054. RSA 2048 would save 4,096 bytes
— the APK signing block is page-aligned, so the cost lands in whole 4 KB pages rather
than tracking the signature size — but NIST rates 2048 as adequate only to around 2030,
which a 2054 certificate outlives by two decades. 3072 falls in the same 4 KB page as
4096, so there is nothing to gain by choosing it.

### Archiving a release

`scripts/archive-apk.sh` builds the release APK and copies it into `local/` named from
the version in the built manifest, alongside a `.sha256`, the R8 `mapping.txt` for that
build, and the signer fingerprint printed for confirmation.

```sh
./scripts/archive-apk.sh          # build, verify, archive
./scripts/archive-apk.sh --force  # replace an existing archive
```

All three files share the `slimpdf-v1.0.apk` prefix, so one release is removed as a unit
and no mapping can be left behind to be matched against the wrong APK later. Keeping the
mapping matters because `release` minifies: without it, an obfuscated stack trace from a
shipped build can never be read back, and the file is written under `app/build/`, which
any clean throws away. `--no-mapping` skips it, for if minification is ever turned off.

It is deliberately not wired into `assembleRelease`. A release build made while working
on a feature would otherwise overwrite the archived APK of the same version, leaving a
file labelled `v1.0` that is not the `v1.0` that shipped — silently. Three things guard
against that: a dirty working tree produces `slimpdf-v1.0-dirty-g1a2b3c4.apk` rather than
the release name, an existing target is never overwritten without `--force`, and an APK
that came out unsigned is refused outright rather than archived under a release name.

## How it works

### Three coordinate spaces

Most of the viewer's complexity is keeping these straight, so `PdfView` names them
explicitly:

- **points** — the PDF's own units, as reported by `PdfDoc`.
- **content px** — points scaled so the widest page exactly fills the view. The page strip
  is laid out once in this space and is _never_ re-laid-out on zoom.
- **device px** — content px times the zoom, offset by the scroll position.

Because layout lives in content space, zooming is a pure change of the draw transform. No
measure pass, no re-layout, no reflow of the page strip.

### Two-tier rendering

Every visible page gets a fit-width bitmap, which is cheap and cached in an `LruCache`
sized to a quarter of the heap. At zoom 1 that bitmap is already 1:1 with the screen.

Past 1.2x those bitmaps would be upscaled and blurry — which is exactly when you are
zooming in to read small print. So a second bitmap covering just the visible slice of the
current page is rendered at the true zoom level and drawn over the top. It is a tile
manager's job done with a single tile, which is all a reader needs and a fraction of the
code. It is rendered 90 ms after the last gesture, so panning and pinching stay smooth and
only the resting view pays for the detail.

`ReaderZoomTest.detailTileSharpensTheZoomedPage` asserts this actually works, by comparing
edge energy in the frame captured before the tile lands against the frame after.

### One render thread

`PdfRenderer` permits exactly one open page at a time and is not thread safe. Every render
therefore funnels through a single-threaded executor owned by `PdfView`; `PdfDoc` guarantees
a page is closed before the next one opens, but does not police which thread calls it.

Page sizes are measured eagerly at open. It costs one open/close per page — well under a
millisecond each — and without it the scroll extent is unknown, which makes the scrollbar
grow as you read.

### Reading positions

`Recents` keeps the list in SharedPreferences as a JSON array via the platform's `org.json`.
A database is the reflex here, but the list is capped at 50 entries and rewritten whole on
every change, so SQLite would buy nothing but bytes.

A position is stored as a page index plus the _fraction_ of that page scrolled past the top,
never as pixels — that is what lets it survive rotation and window resizing. It is written
when a document opens, debounced 1.2 s after each page change, and again on stop, so a
document is never lost if the process is killed while open.

### Documents from other apps

A PDF opened through **Open with** or **Share** arrives as a `content://` URI whose read
grant belongs to the activity it was sent to, and ends with it. Storing that URI in recents
alone makes the entry a dead link by the time it is tapped. All files access does not
revive a lapsed grant either: the storage provider still refuses the URI, which was checked
on an API 36 emulator. So the reader settles, when the document first arrives, on something
that will still be readable later, best first:

1. **Its path on shared storage**, with all files access (`MANAGE_EXTERNAL_STORAGE`, asked
   for once on first launch). The Files app, Downloads and MediaStore URIs map to a path;
   the entry then follows the real file, edits included, and costs no space.
2. **The URI itself**, if the sender made the grant persistable. Few do; the system picker
   always does.
3. **A private copy** in `files/docs/`. Mail and chat attachments live in the sender's
   private storage and cloud documents have no local file, so for those, and for everything
   when access is declined, this is the only option. It costs space and does not follow
   later edits to the original.

Android 10 has no all files access, so there it is 2 or 3.

Both dialogs that ask for the access say why first, because the Settings screen it is
granted on only says what the access allows. If it is switched off after an entry was kept
by its path, reopening that entry asks again. There is no copy to fall back on then — with
the access off the file cannot be read where it is, so it cannot be copied either — so the
alternative offered is to pick the file once in the system picker, whose grant can be kept
for good. The fingerprint (below) matches the picked file to the entry, so the reading
position carries over and the entry switches to the picked URI.

Every entry also stores a content fingerprint: SHA-256 over the length and 64 KB from each
end of the file. Chat apps hand out a fresh URI each time the same file is shared, so the
URI alone would start the document from page one every time; the fingerprint recognises it
and resumes, and the two sightings merge into one entry. The ends are enough because a PDF
ends in its cross-reference table and trailer, which change whenever anything in the file
does; hashing the whole file would mean reading all of it before the first page shows.
Copies are named by that fingerprint, so a document shared ten times is stored once.

A copy is deleted once no entry refers to it, but only after the undo window for a swipe
has closed, and never within a minute of being written, when its entry may not be saved
yet. Copies are left out of backups, so after a restore those entries fall back to their
original URI, which will usually no longer open.

### Why no AndroidX

Nothing here needs it. Day/night comes from resource qualifiers on a platform Material
theme, the list is a `ListView` with a `BaseAdapter`, swipe-to-dismiss and long press are
142 lines in `SwipeRow`, and edge-to-edge insets are 59 lines in `Insets`. AndroidX would
add megabytes to replace about 296 lines.

The instrumented tests _do_ depend on AndroidX Test. Those are `androidTestImplementation`
only and never reach the shipped APK — which is what makes the gestures testable at all,
since SELinux blocks raw event injection on a Play system image.

## Layout

```
app/src/main/java/com/crylo/slimpdf/
  PdfDoc.kt           PdfRenderer wrapper: page sizes, region rendering, spill-to-cache
  PdfView.kt          the viewer: layout, gestures, two-tier rendering
  ReaderActivity.kt   owns the document, chrome, position saving
  RecentsActivity.kt  launcher screen, file picker, removal, favourites
  Recents.kt          the recents store, favourites, private copies of handed-over PDFs
  Sources.kt          content URI to file path, content fingerprints
  SwipeRow.kt         list row: swipe to dismiss, tap, long press
  Insets.kt           edge-to-edge plumbing
```

## License

GPL-3.0. See [LICENSE](LICENSE).
