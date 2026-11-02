package com.clipforge.core.compress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Burns the recording date and time into the picture with FFmpeg's {@code drawtext}, like a dash cam's own stamp.
 * The text is computed per frame from its timestamp ({@code %{pts:localtime:BASE}}), so it stays correct across
 * segments and joined clips. FFmpeg builds for Windows often lack a fontconfig setup, so a font file is passed
 * explicitly.
 */
public final class TimeOverlay {

    private static final List<String> FONTS = List.of(
            System.getenv().getOrDefault("WINDIR", "C:\\Windows") + "\\Fonts\\arial.ttf",
            System.getenv().getOrDefault("WINDIR", "C:\\Windows") + "\\Fonts\\segoeui.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/Library/Fonts/Arial.ttf");

    private TimeOverlay() {
    }

    /** A usable font file, if any. */
    public static Optional<Path> font() {
        return FONTS.stream().map(Path::of).filter(Files::isRegularFile).findFirst();
    }

    /**
     * drawtext filter for a source.
     *
     * @param startEpochMs wall-clock time of the source's first frame
     * @param videoStartUs the source's video start_time; with {@code -copyts} frames keep it in their timestamps
     */
    public static String filter(Path font, long startEpochMs, long videoStartUs) {
        // fractional base: truncating to whole seconds would make the stamp up to one second early
        String base = String.format(Locale.ROOT, "%.3f", (startEpochMs * 1000.0 - videoStartUs) / 1e6);
        // size and position scale with the picture height (expressions are evaluated by drawtext)
        return "drawtext=fontfile='" + escape(font.toAbsolutePath().toString()) + "'"
                + ":text='%{pts\\:localtime\\:" + base + "\\:%Y-%m-%d %H\\\\\\:%M\\\\\\:%S}'"
                + ":fontcolor=white:fontsize=h/28:box=1:boxcolor=black@0.45:boxborderw=6"
                + ":x=h/60:y=h-th-h/60";
    }

    /** Filter-graph escaping for a path inside single quotes: forward slashes, escaped drive colon. */
    static String escape(String path) {
        return path.replace('\\', '/').replace(":", "\\:").replace("'", "'\\''");
    }
}
