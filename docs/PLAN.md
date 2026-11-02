# ClipForge Studio — Implementation Plan

## Context
General-purpose tool for segmented video (dash cams, phones, GoPro, screen recordings). The first test data set is a
dash cam producing ~2 min / 160 MB segments named like `NO20261101-233933-004116F.mp4`: HEVC 2304x1296 ~29.8 fps (VFR)
~10 Mbps, AAC 16 kHz mono (≈1 s shorter than video), and SigmaStar metadata data streams (`ssmd`).

Goals:
1. Merge segments losslessly — one file per continuous session (default) or all selected clips into one, across days.
2. Remove audio or replace it with (looping) background music.
3. Compress 45–50 GB recordings with little visible quality loss.

**Why FFmpeg and not a custom encoder:** HandBrake itself encodes through FFmpeg's libav* plus x265 / NVENC / SVT-AV1;
its C# layer is UI only. ClipForge drives the same engine via the `ffmpeg` CLI, so the UI language (Java) has no
performance impact.

## Architecture
- Maven multi-module: `clipforge-core` (no UI dependencies; future `clipforge-web` will reuse it) and
  `clipforge-desktop` (JavaFX 27 + AtlantaFX).
- FFmpeg is called through `ProcessBuilder` (never via `cmd.exe`), progress via `-progress pipe:1`, all I/O in UTF-8.
- i18n: `ResourceBundle` (`messages.properties` = English default, `messages_tr.properties` = Turkish). Extra languages
  are discovered from the classpath and from a `lang/` folder next to the application.

## Phase 1 — Scanning + lossless merge (v0.1.0)
- `Clip`, `Session` models; `TimestampResolver` (file-name patterns → ffprobe `creation_time` → file mtime);
  `ClipScanner` (multiple folders, marks files still being copied / unreadable, rescan);
  session split when `next.start > prev.start + prev.duration + 5 s`; channel (F/R) and codec/resolution split groups.
- `MergeService`: UTF-8 concat list, `-map 0:v -map 0:a? -ignore_unknown -c copy`, `-fflags +genpts` retry,
  optional `+faststart`. Camera data streams (SigmaStar `ssmd`) cannot be muxed by FFmpeg into MP4/MOV/MKV and are
  dropped; telemetry export is phase 4.
- `FfmpegLocator`, `FfmpegJob` (progress/ETA/cancel), `DiskCheck` (free space, FAT32 4 GB limit).
- UI: add folders, Day ▸ Session ▸ Clip tree with checkboxes and gap markers, merge mode, output folder, job queue.
- Project file (`.cfproj`, JSON) with folders, selections, settings, job queue and job states; atomic auto-save;
  recovery prompt on startup. Merge jobs resume per output file.

## Phase 2 — Compression (v0.2.0)
- Profiles: AV1 NVENC (default, `-preset p6 -tune hq -rc vbr -cq 38 -b:v 0 -multipass qres -spatial-aq 1 -temporal-aq 1`),
  HEVC NVENC (CQ 32, no B-frames), SVT-AV1 (`-preset 6 -crf 35`), x265 (`-preset medium -crf 26`, no B-frames).
  Every profile is test-encoded at startup; only working ones are offered.
- Measured on the real night footage (10 Mbps HEVC 2304x1296, VMAF against the source): AV1 NVENC CQ 36 → 6.8 Mbps /
  VMAF 92, CQ 40 → 4.3 Mbps / 88.7 at 7.5x realtime; SVT-AV1 CRF 35 → 3.8 Mbps / 90.6 at 2x; HEVC NVENC CQ 32 →
  6.4 Mbps / 90.7 at 3.7x. Note: `av1_nvenc` CQ is on a 0-63 scale, so the initially planned CQ 28 produced files
  larger than the source. Two real 2-minute clips: 320 MB → 150 MB (-53 %) in 30 s, frame-exact.
- Segments are encoded with `-ss S -copyts -i <source> -vf trim=start=T0+S:end=T0+E -fps_mode passthrough` where T0 is the
  source's video `start_time` (frame-exact, no overlap; `out_time` is unusable with `-copyts`, so progress counts frames)
  into fragmented MP4 with a 90 kHz timescale. On append each segment is anchored at its planned position, so timing
  errors stay below one frame instead of accumulating; 32-bit `tfdt` boxes are rewritten as 64-bit.
- Three real 2-minute clips (480 MB, 6 min): 233 MB in 38 s (9.5x realtime, 2 parallel segments), 10809/10809 frames.
- Audio is extracted once (stream copy, AAC fallback) and muxed with the video at the end together with
  `+faststart`; while encoding, the growing `.part.mp4` is video only.
- Quality slider + "encode 60 s sample" to estimate final size/time.
- **Segmented encoding with a growing output:** the input timeline is cut into fixed time segments independent of
  source clip length (auto length ≈ 45–60 s of work per segment, or manual 30 s–10 min), keyframe aligned.
  Segments never cross source files (each source is cut on its own timestamps, so joined clips and sources with a
  non-zero start time stay frame-exact). Each segment is encoded to a fragmented MP4 in `<output>.work/`, then appended in order to the main fragmented MP4
  output (only `moof/mdat` boxes, `mfhd` renumbered). The output grows while encoding and is playable at any time.
  Audio is prepared once as a separate track and copied per segment. Up to 2 concurrent segments on NVENC.
- Pause / resume / crash recovery — see [RECOVERY.md](RECOVERY.md).
- The final mux always writes a standard MP4 with `+faststart` (seekable, plays everywhere).

## Phase 3 — Audio (v0.3.0)
- Keep / remove (`-an`) / background music (video stream-copied, only audio encoded, AAC 192k stereo).
- Music playlist, volume, optional fade-out (checkbox + seconds, default 3 s), loop switch (default on) with
  crossfade (0–3 s) built via `acrossfade` chains in a `-filter_complex_script`; long videos use a seamless loop block
  with `-stream_loop -1`. No `-shortest` (it would cut the video); `apad` + `atrim` instead.
- Optional: mix original audio quietly under the music (`amix`).

## Phase 4 — Telemetry and extras (v0.4.0)
- Pluggable `TelemetryParser` (SigmaStar dash cam meta, GoPro GPMF, DJI `.SRT`, phone location tag).
- Map (Leaflet in WebView), speed/acceleration charts, GPX/CSV export, optional date/time/speed overlay (`drawtext`).
- Folder watching, `jpackage` app-image distribution.

## Verification
Unit tests for parsing/grouping/persistence; integration tests on small synthetic clips generated with FFmpeg's
`lavfi` sources; a final end-to-end test with small files only.
