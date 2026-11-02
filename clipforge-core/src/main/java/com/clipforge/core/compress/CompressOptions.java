package com.clipforge.core.compress;

import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.audio.MusicSettings;

import java.util.Locale;

/**
 * @param profile        encoder preset
 * @param quality        CQ/CRF value (lower = better quality, bigger file)
 * @param segmentSeconds segment length; 0 = automatic (about one minute of encoding work per segment)
 * @param concurrency    segments encoded at the same time
 * @param audio          audio handling
 * @param music          background music for {@link AudioMode#MUSIC}
 * @param overlayTime    burn the recording date and time into the picture
 * @param speed          playback speed-up factor ({@link #MIN_SPEED} - {@link #MAX_SPEED}); 1 = normal speed. The
 *                       output is {@code speed} times shorter; the frame rate stays that of the source.
 */
public record CompressOptions(EncoderProfile profile, int quality, int segmentSeconds, int concurrency,
                              AudioMode audio, MusicSettings music, boolean overlayTime, double speed) {

    public static final double MIN_SPEED = 1;
    public static final double MAX_SPEED = 100; // FFmpeg's atempo limit

    public static CompressOptions defaults(EncoderProfile p) {
        return new CompressOptions(p, p.defaultQuality(), 0, p.defaultConcurrency(), AudioMode.KEEP,
                MusicSettings.DEFAULT, false, 1);
    }

    public CompressOptions(EncoderProfile profile, int quality, int segmentSeconds, int concurrency, AudioMode audio) {
        this(profile, quality, segmentSeconds, concurrency, audio, MusicSettings.DEFAULT, false, 1);
    }

    public CompressOptions(EncoderProfile profile, int quality, int segmentSeconds, int concurrency, AudioMode audio,
                           MusicSettings music) {
        this(profile, quality, segmentSeconds, concurrency, audio, music, false, 1);
    }

    public CompressOptions(EncoderProfile profile, int quality, int segmentSeconds, int concurrency, AudioMode audio,
                           MusicSettings music, boolean overlayTime) {
        this(profile, quality, segmentSeconds, concurrency, audio, music, overlayTime, 1);
    }

    public CompressOptions withAudio(AudioMode mode, MusicSettings m) {
        return new CompressOptions(profile, quality, segmentSeconds, concurrency, mode, m, overlayTime, speed);
    }

    public CompressOptions withSpeed(double factor) {
        return new CompressOptions(profile, quality, segmentSeconds, concurrency, audio, music, overlayTime, factor);
    }

    public boolean spedUp() {
        return speed > MIN_SPEED;
    }

    /** Output time for a source time: the source timeline divided by the speed factor. */
    public long outputMs(long sourceMs) {
        return Math.round(sourceMs / speed);
    }

    /** Speed for file names and messages, e.g. {@code 2x} or {@code 2.5x}. */
    public String speedLabel() {
        return formatSpeed(speed) + "x";
    }

    public static String formatSpeed(double s) {
        String t = String.format(Locale.ROOT, "%.2f", s);
        return t.contains(".") ? t.replaceAll("0+$", "").replaceAll("\\.$", "") : t;
    }

    public CompressOptions {
        audio = audio == null ? AudioMode.KEEP : audio;
        music = music == null ? MusicSettings.DEFAULT : music;
        concurrency = Math.max(1, Math.min(4, concurrency));
        // the UI offers 30 s - 10 min; shorter values are allowed for tests and tiny inputs
        segmentSeconds = segmentSeconds <= 0 ? 0 : Math.max(2, Math.min(600, segmentSeconds));
        // projects saved before speed-up existed have no value (0): normal speed
        speed = !(speed >= MIN_SPEED) ? MIN_SPEED : Math.min(MAX_SPEED, Math.round(speed * 100) / 100.0);
    }
}
