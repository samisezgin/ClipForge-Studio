# Changelog

All notable changes are documented here. Versions follow [Semantic Versioning](https://semver.org);
each development phase is a minor release.

## [Unreleased]
### Added
- **Delete source files** (steps panel): on Start a dialog asks whether to delete the sources during the work
  (each source as soon as its part is safely in the output; re-encoding jobs only) or after the work (once the output
  is checked), and whether to move them to the Recycle Bin (default) or delete them permanently, with a prominent
  warning. Cancel starts nothing. Sources another job still needs, or that changed, are kept; the job list shows what
  was deleted.
- Quality ranges are clickable presets (the lowest value of the range is selected).

### Fixed
- Long texts wrap to the panel width instead of being cut off; cut-off single-line texts show a tooltip.
- The side panel cannot be made narrower than its content, and scrolls to the very last line of a tab.
- About text mentions speed-up.

## [1.1.0] - 2026-11-02
### Added
- **Steps panel**: tick Merge, Speed up and Compress in any combination and start them with one button. Merge alone
  stays lossless; with speed-up or compression the clips are joined while encoding (no intermediate file), or each
  clip is processed on its own when Merge is not ticked. The source (selected clips or one file) is chosen here.
- **Speed up** tab: 1×–100× (slider up to 10×, any value can be typed, decimals allowed). The source frame rate is
  kept, the original sound is sped up with its pitch kept, background music plays at normal speed, the date/time
  stamp keeps the real recording time, output names get e.g. `_2.5x`. Resumable like compression. A speed-up without
  compression is encoded close to the source quality.
- Quality has a box for exact values next to the slider; all quality ranges of the chosen encoder are listed and the
  current one is highlighted.
- The clip list fills in while scanning instead of after the whole scan.
- Application logo: window and taskbar icon, About dialog, packaged app icon.

### Changed
- The Merge and Compress tabs hold settings only; everything starts from the steps panel.

### Fixed
- Merged files written by the project could not be listed for compressing them next; finished outputs are now listed
  (not selected automatically), only outputs still being written stay hidden.
- Large fragmented MP4 files (e.g. merge outputs of several GB) showed as unreadable on a slow or busy disk because
  probing timed out; the probe time now grows with the file size.

## [1.0.0] - 2026-11-02
First stable release: all four planned phases (scanning and lossless merge, segmented resumable compression, audio,
telemetry and extras) are complete and reviewed.

### Added
- End-to-end test (`-Dclipforge.e2e=true`) covering scan, merge in all audio modes, compression with stop + simulated
  crash + resume, telemetry export, language switch and folder watching; see docs/E2E.md.

## [0.4.0] - 2026-11-02
### Added
- Telemetry tab completed: dash cam (SigmaStar) clock and GPS data, GoPro GPMF, DJI .SRT and phone location tags;
  route preview, speed chart, GPX/CSV export, OpenStreetMap view in the browser.
- "Burn date and time into the video" for compression: per-frame date/time stamp from the clip start times,
  correct across segments and joined clips.
- Folder watching (View menu, on by default): clips copied into a project folder appear without pressing Rescan.
- Packaging scripts (`packaging/`) producing a self-contained app image with `jpackage`.
- **Retry** for failed jobs (a compression keeps its encoded segments).
- Log files in `~/.clipforge/logs` (Help ▸ Open log folder), including every FFmpeg command line.
- Recovery dialog: "Sources unchanged, continue" for sources that only look changed (copied again, other drive).
- About shows the version, the FFmpeg version and that FFmpeg is a separate program with its own licence.
- Version with commit SHA in the status bar and About dialog, e.g. `v0.4.0 (c22a796)`, embedded at build time.

### Fixed (review)
- GoPro HERO11+ GPS9 data is read; DJI `longtitude` / `altitude` layouts; ISO 6709 degrees-minutes(-seconds).
- Date/time stamp no longer up to a second early; single files use the same start-time rules as scanned clips;
  a missing font is reported when the job is planned.
- Telemetry stays visible across rescans; map page uses pinned Leaflet with integrity hashes and is cleaned up.
- Folder watching survives removed drives and never reports for an old folder list.
- File-time shifts of exactly one hour (FAT32 cards around DST changes) no longer count as a changed source.
- Appending to the growing output retries when a video player briefly locks the file.
- Packaging builds from a clean tree and reads the version from the POM.
- Sources and music files are checked before every run, also when resuming within the same session; a change asks
  the user instead of mixing old and new material.
- ClipForge's own outputs and growing `.part` files are never listed as new clips (output folder = source folder).
- Removing the last folder during a scan, or opening another project while a compression is being planned, no longer
  leaves stale clips or queues jobs into the wrong project.
- Stop now followed quickly by Resume no longer leaves the job stuck; starting a job while pausing keeps the pause.
- "Remove audio" saved by 0.2 projects is kept; unknown merge modes or job states load safely.
- A running sample encode is stopped on exit; status texts match the recovery behaviour.
## [0.3.0] - 2026-11-02
### Added
- Audio panel shared by merging and compression: keep original audio, remove it, or background music.
- Music playlist (MP3, M4A, FLAC, WAV, …) with add / remove / reorder and total length.
- Loop switch (default on): short music repeats until the video ends with a seamless crossfade (0-3 s) at every
  restart; off: the playlist plays once, then silence.
- Fade-out switch with adjustable length (0.5-30 s, default 3 s), music volume, optional quiet mix of the original sound.
- Merging with music keeps the video stream-copied; only the soundtrack is encoded.

- Telemetry tab (preview, completed in 0.4.0): reads SigmaStar dash cam metadata, GoPro GPMF, DJI .SRT files and
  phone location tags; GPX/CSV export, offline route preview, speed chart, OpenStreetMap view in the browser.

### Changed
- The Compress tab's keep/remove audio choice moved into the shared Audio panel.

### Fixed (review)
- Loop restarts are cut at the measured length of the rendered playlist (VBR MP3 headers can be seconds off).
- Volume above 100 % and the original-sound mix are limited and never clip; mono tracks keep their level.
- Equal-power crossfades between songs; restart crossfades shrink instead of disappearing for short tracks.
- Without loop, the fade-out happens at the end of the music, not in the silence after it.
- Long playlists are not decoded beyond the video length; RF64 for very long WAV intermediates.
- Typed fade-out length is kept even without pressing Enter; music files are checked when a paused job resumes.
- Intermediate files of merges with music are always removed; partial music settings and unknown audio modes load.

## [0.2.0] - 2026-11-02
### Added
- Compression tab: compress the selected clips (joined in the same pass, grouped like the merge mode) or any video file.
- Encoder profiles AV1 NVENC (default), HEVC NVENC, SVT-AV1 and x265 with constant-quality slider; profiles are
  test-encoded at startup and only working ones are shown. Defaults tuned with VMAF on real dash cam footage.
- Segmented encoding, frame-exact for joined clips and sources with a non-zero start time. Automatic segment length:
  a per-encoder default first, then about one minute of work per segment from the measured speed. Parallel segments
  (2 on NVIDIA GPUs).
- Growing output: finished segments are appended to `<name>.part.mp4`, which can be opened with *Watch* while encoding.
- Pause (lossless), Stop now and crash recovery at segment level, including truncated appends and half-written segments.
- *Encode 60 s sample*: real bitrate, size and time estimate for the chosen settings.
- Keep or remove audio for compressed outputs.

## [0.1.0] - 2026-11-02
### Added
- Clip scanning across multiple folders with parallel ffprobe; files still being copied are flagged and not selectable.
- Start-time detection from file names (dash cam, phone, GoPro, generic dates), `creation_time` tag or file time.
- Session grouping per camera channel with gap detection and format splits; Day ▸ Session ▸ Clip tree.
- Lossless merge (stream copy): one file per session (default) or all selected clips into one file, across days.
- Growing, always-playable fragmented MP4 output by default; optional standard MP4 (`+faststart`).
- Disk space and FAT32 4 GB checks; outputs never overwrite existing files.
- `.cfproj` project files with atomic auto-save, job queue with pause / stop now / resume / cancel,
  recovery of interrupted jobs on start-up.
- English and Turkish UI; extra languages are picked up from `lang/` folders at runtime.
- Dark / light theme, recent projects, FFmpeg location setting.

### Notes
- Camera data streams (GPS/metadata) are not copied: FFmpeg cannot write them to MP4/MOV/MKV.

## [0.0.1] - 2026-11-02
### Added
- Multi-module Maven skeleton (`clipforge-core`, `clipforge-desktop`), JavaFX 27 + AtlantaFX, Java 26.
- Project documentation (plan, recovery design), MIT license.
