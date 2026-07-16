# Glyph Bar — Nothing Phone (4a)

A small app exposing basic manual control of the Phone (4a) Glyph Bar.

## What the hardware actually is

The bar has **seven visible segments**, but only **six are addressable**:

| Segment | SDK channel | Controllable |
|---|---|---|
| 1–6 (white) | `Glyph.Code_25111.A_1` … `A_6` | yes |
| 7 (red) | — none | **no** |

The red segment is the **video-recording privacy indicator**, driven by the camera
stack. The GDK exposes no channel for it, which is a deliberate security property:
if an app could light it, it could fake "recording" — and if it could *suppress* it,
it could record without the indicator. Expect this never to be openable. The user
toggle for it lives in Camera → settings → *Rec. light*, not in any SDK.

The UI renders it as a locked `REC` segment so the mock matches the real bar.

## Device identity (verified against the SDK binary)

| Device | Constant | Internal | Glyph |
|---|---|---|---|
| Phone (4a) | `Glyph.DEVICE_25111` | `A069` | 6-zone bar ← **this app** |
| Phone (4a) Pro | `Glyph.DEVICE_25111p` | `A069P` | 13×13 matrix |

The Pro is a **different device with different hardware**. It needs
`GlyphMatrixManager`, not `GlyphManager` — this app detects it and says so rather
than failing obscurely.

> Note: several published docs/summaries state the (4a) channels are `Glyph.A1`–`A6`.
> That is wrong. They are nested in `Code_25111` and use underscores. The constants
> here were read directly out of `glyph-matrix-sdk-2.0.aar`'s class constant pool.

## Building

```
.\build.cmd
```

Nothing needs to be preinstalled — no JDK, no Android SDK, no Gradle, no admin
rights, no Android Studio. `build.ps1` downloads whatever is missing into a
self-contained `.toolchain\` folder, writes `local.properties`, then builds.
First run pulls ~500 MB and takes several minutes; later runs reuse it (~2 min).
Delete `.toolchain\` to undo it — nothing is installed system-wide.

An existing `JAVA_HOME` (17+) or `ANDROID_HOME` is reused if usable.

| Flag | Effect |
|---|---|
| `-Install` | build, then `adb install` + launch on a connected device |
| `-Clean` | clean build outputs first (keeps the toolchain) |
| `-Release` | unsigned release APK instead of debug |

### Where the APK lands

```
GlyphBar-debug.apk        <- project root; use this one
```

Gradle writes to `app\build\outputs\apk\debug\` and owns that folder, so the
build publishes a copy to the project root as the convenient artifact.

Android Studio also works — open the folder and it'll sync normally.

The SDK is vendored at `app/libs/glyph-matrix-sdk-2.0.aar` (from
[Glyph-Developer-Kit](https://github.com/Nothing-Developer-Programme/Glyph-Developer-Kit)).
It is **not** on Maven — it must stay in `libs/`.

> The Glyph service only serves the **foreground** app, so the bar goes dark when
> you background it. That's the platform, not a bug.

### Why an emulator won't work

Verified against the SDK binary, there are two hard blockers:

1. `Common.is25111()` is `Build.MODEL.equals("A069")` — an emulator reports
   `sdk_gphone64_x86_64` and fails immediately.
2. `GlyphManager` binds to `com.nothing.thirdparty/.GlyphService`, a Nothing OS
   system service that doesn't exist on a stock AOSP image.

Spoofing `Build.MODEL` on a rooted emulator defeats (1) but not (2). An emulator
can confirm the app installs, launches, and renders; it cannot exercise a single
LED. Real hardware is required.

### API key

`AndroidManifest.xml` ships `NothingKey = "test"`, which is accepted for local
development. For a Play release, request a real key from Nothing's developer
programme. On Android 16+ the key is optional.

## Controls

- **Tap any zone** (A1–A6) to toggle it individually
- **All on / All off**
- **Breathe** — hardware breathing animation (`animate`); the service drives it,
  so it continues without the app ticking it
- **Chase** — software sweep, driven by a coroutine, since the GDK has no such primitive
- **Progress** — maps 0–100% onto the bar via `displayProgress`

## Layout

```
app/
  libs/glyph-matrix-sdk-2.0.aar   vendored SDK
  src/main/java/com/kosta/glyphbar/
    GlyphController.kt            SDK lifecycle + bar operations
    MainActivity.kt               Compose UI
```

`GlyphController` owns the whole SDK surface; the UI never touches `GlyphManager`
directly. Add new patterns there.
