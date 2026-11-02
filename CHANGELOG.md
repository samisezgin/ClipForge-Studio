# Changelog

All notable changes are documented here. Versions follow [Semantic Versioning](https://semver.org);
each development phase is a minor release.

## [Unreleased]

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
