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
* **Colour** — 11 cinematic filter presets plus Original, brightness / contrast / saturation / exposure / temperature / tint,
  vignette, sharpen, blur, and a 15 GPU effects (film grain, scanlines, glitch, bloom, light leak, VHS, …), up to 4 stacked per clip, with per-effect intensity.
* **Motion** — per-clip in/out animations (11 types), boundary transitions (fade, slides, zoom, wipe, iris), speed
  from 0.25× to 4× with pitch-corrected audio, freeze frame.
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

Requirements: JDK 17, Android SDK 35, NDK `27.0.12077973`, CMake 3.22.1.

```bash
./gradlew :app:assembleDebug        # build the APK
./gradlew :app:installDebug         # install on a connected device
./gradlew :app:lintDebug            # lint
./tools/run_native_tests.sh         # host tests for the C++ engine (g++, no Android needed)
```

The Gradle wrapper JAR is not checked in; CI uses `gradle/actions/setup-gradle` (see
`.github/workflows/android.yml`).

### Native tests

`tools/native_tests.cpp` exercises the real engine sources on the host: look matrices, gamma, chroma key,
kernel weights, wipe/iris timing, timeline sampling, waveform bucketing, gain ramps and mixing.

```
CutVideo native engine tests
============================
72 checks, 0 failures
```

## Honest limitations

* **Reverse playback** and **stabilisation** require random-access decoding / device gyro support that this
  build does not implement. The buttons are labelled *Not available* instead of silently doing nothing.
* **PiP overlays** are preview-only in this build: export adds a warning instead of dropping the overlay
  silently.
* **Reversed clips** export forward, and the export screen warns about it.
* 1440p/4K exports depend on the device encoder; the preflight check warns before you start.
* Hardware effects can be disabled in Settings if a device's GL driver misbehaves; the preview then uses
  the CPU look path.
