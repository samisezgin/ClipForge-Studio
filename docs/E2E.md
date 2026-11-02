# End-to-end test

`EndToEndTest` drives the whole workflow through the application controller on small synthetic clips
(no large files). It is skipped in normal builds; run it with:

```sh
./mvnw -pl clipforge-desktop -am verify -Dclipforge.e2e=true -Dtest=EndToEndTest -Dsurefire.failIfNoSpecifiedTests=false
```

It writes `test-output/e2e/report.txt` and a frame with the date/time stamp (`stamp-7s.png`).

## Scenarios
1. **Scan** a folder with Turkish characters plus a backup folder: 3 sessions (two front-camera sessions with a gap,
   one rear-camera session), a duplicate clip in the backup folder and a file still being copied are recognised.
2. **Merge** per session with original audio, all-in-one (one file per camera channel) without audio, and with looping
   background music (crossfade, fade-out, quiet mix of the original); no intermediate files left behind.
3. **Compress** a session (AV1, 2 s segments, music, date/time stamp), *Stop now* after two segments, then simulate a
   crash on top (half-written append, job still RUNNING in the project file), reopen the project in a new controller
   and resume from the recovery dialog path: every frame exactly once (540/540), growing output removed.
4. **Telemetry**: DJI `.SRT` sidecar read and exported as GPX.
5. **Language switch** (Turkish/English) and **folder watching** picking up a clip added while the app runs.

## Last run (v1.0.0)
All 5 scenarios passed in 41 s (Windows 11, FFmpeg 9.0, RTX 4070 Ti). The packaged app image built from a clean tree
starts. Large real recordings (45–50 GB) are not part of this test; try pause / close / reopen / resume once with your
own footage.