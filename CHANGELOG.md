# Changelog

All notable changes are documented here. Versions follow [Semantic Versioning](https://semver.org);
each development phase is a minor release.

## [Unreleased]

## [0.2.0] - 2026-11-02
### Added
- Compression tab: compress the selected clips (joined in the same pass, grouped like the merge mode) or any video file.
- Encoder profiles AV1 NVENC (default), HEVC NVENC, SVT-AV1 and x265 with constant-quality slider; profiles are
  test-encoded at startup and only working ones are shown. Defaults tuned with VMAF on real dash cam footage.
- Segmented encoding with automatic segment length (about one minute of work per segment, from the measured speed)
  and parallel segments (2 on NVIDIA GPUs).
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
