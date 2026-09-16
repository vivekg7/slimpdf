# SlimPDF

A PDF reader for Android with no runtime dependencies at all — no AndroidX, no Compose,
no third-party PDF engine. The release APK is **70 KB**, and the whole app is 1,351 lines
of Kotlin compiling to 490 methods in a single dex.

|                      |         |
| -------------------- | ------- |
| Release APK          | 70 KB   |
| Methods              | 490     |
| Runtime dependencies | none    |
| minSdk / targetSdk   | 29 / 36 |

## What it does

- Open a PDF from the system picker, or via **Open with** / **Share** from any other app.
- Continuous vertical scrolling with fling, pinch zoom and double-tap zoom.
- Recents list showing where you left off in each document; swipe a row aside to remove it,
  with an undo.
- Resumes at the exact scroll position, and keeps it across rotation and process death.
- Follows the system light/dark setting.

## What it deliberately does not do

No text search, no text selection, no copy. `android.graphics.pdf.PdfRenderer` — the
platform's built-in engine — rasterises pages and exposes no text layer, so any of those
features would mean bundling PdfBox-Android or an NDK build of MuPDF/PDFium. That is
8–15 MB against a 70 KB app, which is the opposite of the point. If you need search, this
is the wrong reader.

Encrypted PDFs also cannot be opened, for the same reason: `PdfRenderer` rejects them.

## Building

Requires JDK 17+ and the Android SDK. Point Gradle at the SDK with either `ANDROID_HOME`
or a `local.properties` containing `sdk.dir=/path/to/Android/sdk` (git-ignored).

```sh
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleRelease      # minified, resource-shrunk release APK
./gradlew :app:connectedAndroidTest # instrumented tests, needs a device or emulator
```

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

### Why no AndroidX

Nothing here needs it. Day/night comes from resource qualifiers on a platform Material
theme, the list is a `ListView` with a `BaseAdapter`, swipe-to-dismiss is 118 lines in
`SwipeRow`, and edge-to-edge insets are 59 lines in `Insets`. AndroidX would add megabytes
to replace about 270 lines.

The instrumented tests _do_ depend on AndroidX Test. Those are `androidTestImplementation`
only and never reach the shipped APK — which is what makes the gestures testable at all,
since SELinux blocks raw event injection on a Play system image.

## Layout

```
app/src/main/java/com/crylo/slimpdf/
  PdfDoc.kt           PdfRenderer wrapper: page sizes, region rendering, spill-to-cache
  PdfView.kt          the viewer: layout, gestures, two-tier rendering
  ReaderActivity.kt   owns the document, chrome, position saving
  RecentsActivity.kt  launcher screen, file picker, swipe-to-remove
  Recents.kt          the recents store
  SwipeRow.kt         swipe-to-dismiss list row
  Insets.kt           edge-to-edge plumbing
```

## License

GPL-3.0. See [LICENSE](LICENSE).
