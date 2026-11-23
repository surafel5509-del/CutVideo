# CutVideo — Final Report

Scope: P1 crash/bug fixes, P2 CapCut-style tools, P3 pro-tool coverage, P4 architecture, P5 testing.
Status vocabulary (per the task): **COMPLETE AND TESTED** · **IMPLEMENTED BUT NOT FULLY TESTED** ·
**PARTIALLY IMPLEMENTED** · **NOT IMPLEMENTED**.

Environment honesty up front: this work was done in a sandbox with **no Android device or emulator**
(only GitHub-hosted CI builds). "Tested" below therefore means: compiled clean in CI (`assembleDebug`),
JVM unit tests green, native engine tests green (132 checks), and root causes verified against the
exact Media3 1.11.1 sources the app builds against. **On-device verification of the fixes and tools
is still owed** (import → play → edit → export on a real phone); everything that could not be
executed is labelled accordingly.

---

## 1. Export button crash (P1)

**Status: IMPLEMENTED BUT NOT FULLY TESTED** (root cause proven against Media3 1.11.1 source; build +
unit tests green; on-device export run pending)

Two independent root causes, both found in the code path click → `ExportController.preflight()` →
`TransformerExporter.buildPlan()` → `Transformer.start()`:

- **Root cause A — illegal `EditedMediaItem` combination.** Media3 1.11.1 `EditedMediaItem`'s
  constructor runs `checkState(!containsSpeedChangingEffects(builder.effects, /* includeSpeedScaling= */ false))`
  whenever a non-default `SpeedProvider` is set. The exporter set **both** `SpeedChangeEffect` and
  `setSpeed()` for sped-up clips → `IllegalStateException` at plan time. `preflight()` caught only
  `ExportException`, so the exception escaped and crashed the Export screen.
  **Fix:** speed is now applied exactly once, via `setSpeed(SpeedProvider)` (`FixedSpeedProvider`, or
  `RampedSpeedProvider` for speed curves). `preflight()`/`refreshNotes()` now surface any plan failure
  as a note instead of crashing.
- **Root cause B — wrong-thread Transformer access.** `Transformer.start()`, `getProgress()` and
  `cancel()` call `verifyApplicationThread()`. The Builder defaulted the Transformer looper to the
  main looper while the calls ran on `Dispatchers.Default` → guaranteed `IllegalStateException`.
  **Fix:** a dedicated `HandlerThread("cutvideo-export")` owns the Transformer for the whole export
  (Builder `setLooper`, start/progress-poll(250 ms)/cancel posted to it, shutdown with `quitSafely`).

Also fixed in the export path: `Crop`/`Scale` effects are clamped so Media3's `checkArgument`s cannot
throw on degenerate regions; audio-track clips are positioned with `EditedMediaItemSequence.addGap()`
(exact silence) instead of a lead-silence processor; `cancel()` is thread-safe and deletes the output
file; a `Transformer` progress poller drives the UI progress bar; `ExportException` carries mapped
encoder/decoder/muxer error descriptions.

Expected behaviour now: **progress is shown**, errors are meaningful (preflight notes + mapped
encoder errors), **cancellation works** (Transformer cancelled on its own looper, partial file
deleted), concurrent exports are prevented by `ExportController`'s state machine (all state
transitions go through one mutex + `ApplicationScope`), and resources are released on
completion/error/cancel.

## 2. Imported video frozen in preview (P1)

**Status: IMPLEMENTED BUT NOT FULLY TESTED** (root cause proven against Media3 1.11.1 source; unit
tests for the shader conversion; on-device playback run pending)

- **Root cause (verified):** `DefaultVideoFrameProcessor.createFocusedEglContextWithFallback` creates
  an OpenGL **ES 3.0** context and falls back to **ES 2.0** on `GlException`/`EGL_BAD_MATCH`. Every
  CutVideo shader (`cutvideo_effect*.glsl`, overlay pair) was `#version 300 es` only — and even the
  "fallback blit" recompiled the same ES3 vertex shader. On an ES 2.0 context every compile fails
  (`handle = 0`, `blitHandle = 0`), `draw()` returns without drawing, and the frame stays at the
  transparent-black clear → **the preview freezes/blackens while audio and the timeline keep
  running** — exactly the reported symptom.
- **Fix:** `GlslCompat` derives an ES 2.0 variant of each shader at runtime (deterministic,
  JVM-tested, rejects ES3-only constructs loudly); the blit fallback ships in both variants;
  `init()` tries ES3→ES2 in either order; a last-resort passthrough blit keeps frames flowing even
  if the look shader cannot build (never a black preview). Two further real bugs fixed along the
  way: `uTexelSize` was written with no program bound (`GL_INVALID_OPERATION`) — now applied while
  the program is current; and the preview's GPU effect captured a stale project aspect — now
  rebound when the aspect changes.

Expected behaviour: first frame shown, play/pause/scrub work, timeline and preview stay synced (the
playlist mapping was audited against `enablePlaylistMode` and is correct). No fake previews or static
thumbnails were substituted.

## 3. Mask tool (P2)

**Status: IMPLEMENTED BUT NOT FULLY TESTED** (logic unit-tested; GPU result not device-verified)

- Circle, rectangle, linear, mirror (reflection-style: band + reflected content) and freehand
  (paintable strokes, kept editable) with position, scale, rotation, feather, opacity,
  expand/contract, invert, rect roundness. Up to two masks stack (the shader budget; the UI says so).
- **They cut real pixels:** `cutvideo_effect.glsl` computes per-pixel coverage and multiplies the
  frame's alpha in the shared `CutVideoGlEffect`, so preview and export run the identical code path.
  Freehand strokes rasterise to a coverage texture (`MaskRasterizer`, unit-tested) uploaded only when
  strokes change.
- Mask keyframes (X/Y/scale/rotation/feather/opacity/expansion) animate in both renderers.
- Known limit: keyframe tracks animate the first mask (documented).

## 4. Keyframe tool (P2)

**Status: IMPLEMENTED BUT NOT FULLY TESTED** (engine unit-tested; visual result not device-verified)

- 17 properties: position X/Y, scale, rotation, opacity, volume, blur, glow, effect intensity, crop
  (4 edges), mask X/Y/scale/rotation/feather/opacity/expansion, speed. Add/move/delete keys, per-key
  interpolation: linear, ease-in, ease-out, ease-in-out, hold, custom cubic Bézier (CSS-style, solved
  with Newton + bisection — unit-tested).
- Timeline shows keyframe diamonds on any clip with keys. Persistence: tracks live on the clip in the
  serialised `ProjectDoc`, so they survive save/reload and draft recovery.
- One evaluation engine (`KeyframeEngine`) drives **preview and export**, so they cannot drift:
  transform/opacity animate the preview's `graphicsLayer` and the export's shader transform
  uniforms; volume drives the preview mixer and `GainAudioProcessor`; speed drives
  `PlaybackParameters` in the preview and a real Media3 `SpeedProvider` in the export.

## 5. Graph / curve editor (P2)

**Status: IMPLEMENTED BUT NOT FULLY TESTED** (curve math unit-tested; interaction not device-verified)

- Renders the actual evaluated curve (through `KeyframeEngine.sampleCurve`), draggable keyframe dots
  (retime + revalue), draggable Bézier handles, tap-to-seek, playhead line, value range readout,
  interpolation picker, and a "slow ramp" preset that builds a real speed ramp.
- Velocity graph for speed: the SPEED curve is a first-class property; in export it is applied as a
  piecewise speed ramp (`RampedSpeedProvider`), in preview continuously via `playbackParameters`.
  The source↔presentation time mapping while ramping is piecewise-linear (documented approximation).

## 6. Revert tool (P2)

**Status: IMPLEMENTED BUT NOT FULLY TESTED** (undo integration exercised via unit-tested command
algebra; UI flow not device-verified)

- Property-level revert groups: transform, colour & filters, effects & blur, masks, keyframes, audio,
  speed, chroma key — plus whole-clip revert, each with confirmation. Restores from an
  import-time snapshot (`ClipOriginal` recorded when the clip is created); **the original media file
  is never touched**; every revert goes through `mutate()` so it integrates with undo/redo.

## 7. Manual photo background remover (P2)

**Status: IMPLEMENTED BUT NOT FULLY TESTED** (engine unit-tested with 8 behaviour tests; UI not
device-verified)

- Real pixel engine (`CutoutEngine`, pure Kotlin): erase + restore brushes with size, hardness
  (hard edge → gaussian falloff) and opacity, stroked with interpolated stamps; stroke-level
  undo/redo (24 deep); edge refinement that only touches the edge band; feathering; the original
  pixels are immutable and every result is composited from them.
- UI: checkerboard alpha preview, tool modes (erase/restore/move), zoom (1–8×) and pan, brush
  sliders, undo/redo, refine/soften/start-over, live "visible %" readout.
- **Transparent PNG export** to `Pictures/CutVideo` via MediaStore (alpha preserved).
- **Usable as a layer:** the PNG/photo can be placed on the timeline as an image overlay layer that
  renders in the preview (Coil). *Limit:* composited overlay export is inside the app's pre-existing
  documented PiP limitation (Media3 Transformer 1.11 cannot flatten multi-video-item compositions) —
  the export warns rather than pretending.

## 8. Additional pro tools (P3)

**Status: PARTIALLY IMPLEMENTED** (the app already shipped a large toolset before this session; the
session added masks/keyframes/graph/revert/cutout/speed-ramps; remaining gaps listed honestly)

Present and working through the real pipeline (pre-existing + this session):
split/trim/duplicate/reorder, multi-track timeline (video/overlay/text/audio), overlays, audio tracks,
freeze frame, speed 0.25–4× + **speed ramps (new)**, snapping, crop/rotate/flip, volume/fades/clip
trim/waveforms/audio extraction/voice-over, 23 GPU effects + 26 filters, blur/sharpen/vignette/grain/
bloom(glow)/VHS/glitch and more, colour adjustments + one-tap auto enhance, 3-D LUT native engine,
10 transitions, text layers with fonts/outline/shadow/animation + SRT round-trip, chroma key, aspect
presets, autosave, undo/redo, draft recovery, export resolution/fps/bitrate controls.

Not implemented (reported instead of faked):
- **Reverse playback** (needs random-access decoding) — the button is labelled *Not available*.
- **Stabilisation** (needs device sensors) — labelled.
- **Ripple editing and clip grouping** — not implemented.
- **Audio noise reduction** — not implemented.
- **Colour curves / per-channel RGB split / arbitrary blend modes** — not implemented (the graph
  editor animates properties, it is not a colour-curves tool).
- **Compositing overlays into the export** — pre-existing limitation (see §7).

## 9. Architecture & consistency (P4)

**Status: COMPLETE AND TESTED** (as a code-level property: one document, shared shader, shared
evaluation engine — enforced by the build and unit tests)

- The project structure, Kotlin/JNI/C++ split, CMake, MediaCodec/Extractor/Muxer, GLES pipeline and
  Media3 Transformer/ExoPlayer architecture were preserved; nothing was rebuilt.
- Preview and export share `ProjectDoc`, `TimelineManager`, `AnimationEngine` + `KeyframeEngine`, the
  same `CutVideoGlEffect`/`cutvideo_effect.glsl` (with both ES variants), `TextRasterizer`, and the
  same gain/fade semantics — pixel-level behaviour is defined once.
- No UI-thread blocking added (export runs on its own HandlerThread; imports/analysis on IO
  dispatchers; the brush engine updates only dirty regions).

## 10. Testing (P5)

**Status: PARTIALLY IMPLEMENTED** — automated suites complete and green; device testing NOT possible
in this environment and remains outstanding.

| Suite | Result |
|---|---|
| Native engine tests (`tools/run_native_tests.sh`, g++) | **132 checks, 0 failures** (ran locally + in CI) |
| JVM unit tests (`:app:testDebugUnitTest`) | **green in CI** — 20 pre-existing + 19 new (GlslCompat 5, CapCut tools 14) |
| CI pipeline (`.github/workflows/android.yml`) | **green**: native tests, `assembleDebug`, unit tests, APK artifact |
| Device/playback/export/perf testing | **Not possible here** — no device/emulator; must be done on hardware: import → preview → each new tool → export → play the output and check A/V sync |

---

### Summary table

| # | Item | Status |
|---|---|---|
| 1 | Export crash (root-caused, progress/errors/cancel/resources) | IMPLEMENTED BUT NOT FULLY TESTED |
| 2 | Preview frozen (root-caused, ES2/ES3 shader pipeline) | IMPLEMENTED BUT NOT FULLY TESTED |
| 3 | Mask tool (5 types, real alpha, keyframable) | IMPLEMENTED BUT NOT FULLY TESTED |
| 4 | Keyframe tool (17 properties, 6 interpolations, persisted) | IMPLEMENTED BUT NOT FULLY TESTED |
| 5 | Graph/curve editor (drag keys + Bézier handles, speed ramps) | IMPLEMENTED BUT NOT FULLY TESTED |
| 6 | Revert (per-group + whole-clip, undo/redo integrated) | IMPLEMENTED BUT NOT FULLY TESTED |
| 7 | Photo background remover (brush engine + PNG + layer) | IMPLEMENTED BUT NOT FULLY TESTED |
| 8 | Additional pro tools (P3) | PARTIALLY IMPLEMENTED (list of gaps in §8) |
| 9 | Architecture & preview/export consistency (P4) | COMPLETE AND TESTED (code-level) |
| 10 | Testing (P5) | PARTIALLY IMPLEMENTED (automated green; device tests outstanding) |

Commits on `arena/9ff4bf73-cutvideo`: `d04c4bf` (bug fixes), `f8f19ea` (tools), `b6bac12` (CI fixes +
docs). CI run 45277844509 is green and uploads the debug APK artifact.
