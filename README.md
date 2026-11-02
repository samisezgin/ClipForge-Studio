# ClipForge Studio

Desktop tool for working with segmented video recordings (dash cams, phones, action cameras, screen recorders):

- **Merge** clip segments losslessly (stream copy) — per continuous session or all selected clips into one file.
- **Re-score audio** — keep, remove, or replace with a looping music playlist (seamless crossfade at every restart, optional fade-out, volume, optional quiet mix of the original sound) — for merging and compression.
- **Compress** large recordings with AV1/HEVC (NVENC, SVT-AV1, x265) in resumable segments; the output grows while encoding and can be watched, pause/stop/crash lose at most the segments in progress.
- **Telemetry** (GPS/speed) visualisation where the camera embeds it. *(planned, phase 4)*

All heavy lifting is done by [FFmpeg](https://ffmpeg.org); ClipForge is the workflow and UI around it.

## Requirements

- JDK 26
- FFmpeg (tested with 9.0; `ffmpeg` and `ffprobe` on `PATH` or chosen under *Tools ▸ FFmpeg location*)
- Optional: NVIDIA GPU with NVENC for hardware encoding

## Build & run

```sh
./mvnw verify                                  # build + tests
./mvnw -pl clipforge-desktop javafx:run        # start the desktop app
```

On Windows use `mvnw.cmd`.

## Project layout

| Module | Purpose |
|---|---|
| `clipforge-core` | UI-independent services: scanning, FFmpeg jobs, merging, project files, i18n bundles |
| `clipforge-desktop` | JavaFX + AtlantaFX desktop UI |

A future web front-end can depend on `clipforge-core` only.

## Languages

The UI is fully translatable. See [CONTRIBUTING.md](CONTRIBUTING.md#adding-a-language).

## Documentation

- [Implementation plan](docs/PLAN.md)
- [Pause & recovery design](docs/RECOVERY.md)

## License

[MIT](LICENSE)
