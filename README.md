# CutVideo — Android video editor

CutVideo is a complete, production-shaped video editor for Android: a real multi-track timeline, a real
ExoPlayer preview, a real Media3 Transformer export, a C++17/NDK rendering core behind JNI, and a dark,
violet-accented Compose UI.

Everything the UI shows comes from the project document. There are no mockups, no fake progress bars and
no simulated rendering — if a capability is missing on the device, the app says so instead of pretending.

```
        ┌──────────────────────────── one ProjectDoc ────────────────────────────┐
        │  tracks → clips → transforms, looks, effects, text, audio, animations   │
        └───────────────┬──────────────────────────┬──────────────────┬──────────┘
                        │                          │                  │
              PreviewController           TransformerExporter    NativeEngine (C++)
              ExoPlayer playlist          Media3 Transformer     JNI: looks, kernels,
              + GL effect + PiP/text      HW decode/encode       timeline math, audio
```

## Screens

| Screen | What it does |
| --- | --- |
| **Splash** | Animated brand intro, then Home. |
| **Home** | Recent projects and drafts (real Room rows with rendered covers), templates that create real timelines, plus entry points to the media library, exports and settings. Long-press a project to rename/duplicate/delete. |
| **Media Picker** | MediaStore browser with real thumbnails (Coil video frames), multi-select import, and a SAF document-picker fallback. Asks for `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` only when needed. |
| **Editor** | Live preview, transport, multi-track timeline, tool rail and per-tool panels (trim, split, speed, adjust, filters, effects, animation, transition, crop, chroma key, PiP, audio, captions). |
| **Text & Captions** | Full text studio: fonts, size, colour, background, alignment, shadow, outline, position, rotation, timing, animation, plus SRT import/export. |
| **Audio** | Decoded waveform peaks, volume, fade in/out, mute, audio extraction, music import, and a real `MediaRecorder` voice-over recorder. |
| **Effects library** | Full-screen filters, GPU effects, transitions and animations, each card rendered through the same native look pipeline as the export. |
| **Export** | Resolution / frame-rate / codec selection, real preflight warnings, measured progress + ETA, cancel, then publish to the gallery through MediaStore. |
| **Exported Videos** | Every export with play / share / delete. |
| **Settings** | Preview quality, GPU effects, snapping, haptics, waveforms, defaults, autosave, storage and native-engine diagnostics. |

## Editing capabilities

* **Timeline** — 4 track types (video, overlay, text, audio), drag to move, trim handles, split at the
  playhead, duplicate, delete, snapping to clip edges and the playhead, pinch zoom, filmstrip thumbnails,
  waveform strips, add-track from the clip menu.
* **Colour** — 26 filter presets plus Original (cinematic, teal & orange, noir, sepia, pastel, food, sky,
  neon night, sunset, iced, fade, HDR, clays and more), brightness / contrast / saturation / exposure /
  temperature / tint, vignette, sharpen, blur.
* **Effects** — 23 GPU effects (film grain, scanlines, glitch, bloom, light leak, VHS, snow, zoom blur,
  blur, pixelate, neon, halftone, tilt shift, old film, heat wave, spotlight, …), up to 4 stacked per clip
  with per-effect intensity and a clear message when the stack is full.
* **Motion** — per-clip in/out animations (18 types incl. shake, wobble, bounce, spin, flip and glitch),
  boundary transitions (10 types: fade, slides, zoom, wipe, iris, glitch cut, blur dissolve), speed
  from 0.25× to 4× with pitch-corrected audio, freeze frame.
* **Pro tools** — copy / paste a clip's look, apply one look to every clip, one-tap auto enhance measured
  from a real frame (exposure, contrast, saturation, detail), send a clip to the overlay track, detach audio.
* **Masks** — circle, rectangle, linear, mirror (reflection-style) and freehand masks with position, scale,
  rotation, feather, opacity, expand/contract, invert and corner roundness. Up to two stack, and they cut
  real alpha in the GLSL render pipeline — the same code path runs in the preview and the export.
* **Keyframes** — 17 animatable properties (position X/Y, scale, rotation, opacity, volume, blur, glow,
  effect intensity, crop, mask position/scale/rotation/feather/opacity/expansion, speed) with per-keyframe
  interpolation (linear, ease in/out/in-out, hold, custom Bézier). Keyframes are persisted with the project
  and shown as diamonds on the timeline. Preview and export share one evaluation engine.
* **Graph / curve editor** — drag keyframe dots to retime/revalue, drag Bézier handles for custom velocity,
  tap to seek, and build real speed ramps: SPEED curves are applied by a Media3 `SpeedProvider` in the export
  and continuously in the preview.
* **Revert** — per-property-group or whole-clip restore from the import-time snapshot with confirmation.
  The original media file is never modified, and every revert is undoable.
* **Photo cutout** — manual background remover: erase/restore brushes (size, hardness, opacity), move
  (zoom/pan) mode, stroke undo/redo, edge refinement and feathering, a true checkerboard alpha preview,
  transparent PNG export to Pictures/CutVideo, and the result can be added to the timeline as an image layer.
* **Composition** — crop, rotate, scale, chroma key (key colour, similarity, smoothness, spill), and
  picture-in-picture for overlay clips.
* **Audio** — per-clip volume, fades, mute, master volume, extracted audio tracks, imported music, recorded
  voice-over.
* **Text** — six font families, colour/background palettes, outline, shadow, alignment, letter spacing,
  line height, position, rotation, animation, SRT round-trip.

## Architecture

```
app/src/main/java/com/cutvideo/editor
├── core/            formatting helpers (timecode, bytes, dates)
├── data/            Room entities/DAOs, repositories, DataStore settings
├── di/              AppContainer — manual DI, process-scoped engines
├── domain/
│   ├── model/       ProjectDoc, Clip, specs, filter/effect/transition catalogs
│   ├── timeline/    TimelineManager — the pure editing algebra (testable, no Android deps)
│   ├── history/     UndoRedoManager
│   ├── export/      ExportSettings / ExportState
│   └── subtitle/    SrtCodec
├── nativebridge/    JNI façade, param writer, timeline encoder, fallbacks
├── media/           MediaProbe, ThumbnailStore, PcmDecoder, MediaImporter, VoiceRecorder
├── editor/          EditorController (commands + autosave), PreviewController (ExoPlayer), AnimationEngine,
│                    TextRasterizer, gl/ (GL effect, shaders, overlay compositor)
├── export/          TransformerExporter, MediaStoreSaver, ExportController, ExportService, audio processors
└── ui/              theme, reusable components, screens, navigation (CutVideoRoot)
```

* **One document, two renderers.** Preview and export both consume `ProjectDoc`; `TimelineManager` is pure
  Kotlin so cut/trim/move/split/snap behaviour is identical everywhere and unit-testable.
* **Preview** is a single `ExoPlayer` playing the main video track as clipped `MediaItem`s, with follower
  players for the active overlay/audio clips, a `GlEffect` colour pipeline, and Compose text layers.
* **Export** is Media3 Transformer (hardware decode/encode): per-clip `Effects` for speed, colour and
  geometry, `OverlayEffect` for rasterised text, and extra gapless sequences for audio tracks with gain and
  fade processors.
* **Native core** (`app/src/main/cpp`) implements the colour-science matrices, kernels (vignette, sharpen,
  blur, chroma key, wipe), the timeline evaluator and audio helpers. Every entry point degrades gracefully:
  if the library is missing, Kotlin fallbacks take over and the editor keeps working.
* **Persistence** is Room for projects/media/exports and DataStore for settings; the editor autosaves with
  debounce, records undo checkpoints per gesture, and persists the playhead position.

## Build

Verified in CI (`.github/workflows/android.yml`): **assembleDebug + unit tests green**, debug APK
produced. Toolchain: JDK 17, AGP 8.10.1, Gradle 8.11.1, Kotlin 2.2.20, KSP 2.2.20-2.0.2,
compileSdk/targetSdk 36, minSdk 26, Media3 1.11.1, Compose BOM 2025.09.00, NDK `27.0.12077973`,
CMake 3.22.1.

```bash
./gradlew :app:assembleDebug        # build the APK
./gradlew :app:installDebug         # install on a connected device
./gradlew :app:lintDebug            # lint
./tools/run_native_tests.sh         # host tests for the C++ engine (g++, no Android needed)
```

The Gradle wrapper JAR is not checked in; CI uses `gradle/actions/setup-gradle` with Gradle 8.11.1 and
installs the SDK/NDK through `sdkmanager`. If a build fails, the workflow posts the compiler errors as a
commit comment so they can be read without the raw job log.

### Native tests

`tools/native_tests.cpp` exercises the real engine sources on the host: look matrices, gamma, chroma key,
kernel weights, wipe/iris timing, timeline sampling, waveform bucketing, gain ramps and mixing.

```
CutVideo native engine tests
============================
132 checks, 0 failures
```

The suite also asserts that every effect id, motion curve and transition declared in the native enums is
reachable and ends in a fully visible state, so the Kotlin and C++ implementations cannot drift apart.

### JVM tests

`app/src/test` runs 20 JUnit tests on the build machine (`gradle :app:testDebugUnitTest`, executed by CI
before the APK is uploaded):

* `CatalogIntegrityTest` — the effect catalogue, the shader ids and `NativeEffectIds` stay index-aligned,
  filter matrices are well formed, every motion/transition enum value has a preset.
* `AnimationEngineTest` — all 18 motions end fully visible and stay in range, all 10 transitions complete,
  animation windows clamp correctly.
* `TimelineManagerTest` — split/trim/duplicate/delete, magnetic gap closing, snapping and frame stepping.

CI fails if the report is missing, if any test fails, or if fewer than 20 tests ran — a green run is proof
the suite actually executed.

## Honest limitations

* **Reverse playback** and **stabilisation** require random-access decoding / device gyro support that this
  build does not implement. The buttons are labelled *Not available* instead of silently doing nothing.
* **PiP overlays** (video and image) are preview-only in this build: export adds a warning instead of
  dropping the overlay silently. Compositing overlays into the exported file needs a video compositor that
  Media3 Transformer 1.11 does not provide for multi-video-item compositions.
* **Reversed clips** export forward, and the export screen warns about it.
* **Speed ramps** map curve time to media time piecewise-linearly in the export's `SpeedProvider`; for
  strongly varying ramps the mapping is an approximation (documented in `RampedSpeedProvider`). The preview
  follows the same curve through `playbackParameters`.
* **Mask keyframes** animate the first mask (the shader carries two masks; keyframe tracks are per property).
* 1440p/4K exports depend on the device encoder; the preflight check warns before you start.
* Hardware effects can be disabled in Settings if a device's GL driver misbehaves; the preview then uses
  the CPU look path. The GLSL pipeline ships ES 3.0 and ES 2.0 shader variants (derived by `GlslCompat`)
  because Media3 falls back to an ES 2.0 context on some devices; if the look shader still fails to build
  on a driver, the renderer falls back to a pass-through blit so preview and export never turn black.
* **Auto enhance** analyses one frame per clip (mean/std/colourfulness) — it is adaptive colour grading,
  not scene or face detection.
* Exports retry once with H.264 when the device's H.265 encoder fails; the export screen reports the swap.
