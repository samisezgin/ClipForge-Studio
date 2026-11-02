package com.clipforge.core.ffmpeg;

import java.nio.file.Path;

/** Resolved locations of the FFmpeg executables. */
public record FfmpegTools(Path ffmpeg, Path ffprobe, String version) {
}
