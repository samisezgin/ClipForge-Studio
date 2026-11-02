# Pause & Recovery Design

Recovery runs on startup and whenever **Resume** is pressed. No watermark or marker is written into the video;
progress is derived from two independent sources that cross-check each other:

1. **Job record in the project file (`.cfproj`)** — source fingerprint (path + size + mtime), settings hash,
   segment table `{index, start, end, state: PENDING / ENCODING / ENCODED / APPENDED, temp file}` and the
   **committed point** (`committedBytes` = output size after the last successful append, plus `committedTime`).
2. **The output file itself** — every `moof` box carries a `tfdt` (decode time of the fragment). Even if the project
   file is lost, *Recover from partial output* finds the last valid fragment and the second where work stopped.

## Write ordering (crash safe)
append segment to output → `FileChannel.force()` → atomically update project file (`APPENDED`, new
`committedBytes`) → delete temp segment. A crash at any step is resolved consistently by the scenarios below.

## Recovery scenarios
1. **Source or settings changed** — changed source: ask the user (resume / restart). Changed encoding settings:
   restart only (segments encoded with different settings cannot be joined).
2. **Output larger than `committedBytes`** — crashed during an append: truncate to `committedBytes`.
3. **Output smaller than `committedBytes` or box scan fails** — scan from the start, truncate after the last intact
   `moof+mdat`, derive which segments really exist from `tfdt`, fix the segment table.
4. **`ENCODED` segment** — verify its temp file (duration as expected, last fragment complete); keep it if valid,
   otherwise re-encode.
5. **`ENCODING` segment** — delete the partial temp file, back to `PENDING`.
6. **Normal continuation** — append ready segments in order, encode the missing ones. The UI shows e.g.
   "04:12:30 of 11:05:00 ready, ~1 h 10 min left".

## Pausing
- **Pause (default, lossless):** no new segments are started; running segments finish and are appended.
  The button shows "Pausing… (1 segment left)".
- **Stop now:** FFmpeg receives `q` (killed after 2 s); partial segments are deleted (≤ ~1 min of work lost each).
- A paused job stays in the project file and can be resumed days later.
- Closing the app while a job runs asks: *Pause and exit / Stop now and exit / Cancel*.
- Windows sleep/hibernate: processes are suspended and continue after wake-up.

## Merge jobs (phase 1)
Merging is stream copy and takes seconds to minutes, so it resumes per output file: finished outputs are skipped,
a partially written output is deleted and redone.
