package com.clipforge.core.compress;

import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.audio.MusicSettings;
/**
 * @param profile        encoder preset
 * @param quality        CQ/CRF value (lower = better quality, bigger file)
 * @param segmentSeconds segment length; 0 = automatic (about one minute of encoding work per segment)
 * @param concurrency    segments encoded at the same time
 * @param audio          audio handling
 * @param music          background music for {@link AudioMode#MUSIC}
 * @param overlayTime    burn the recording date and time into the picture
 */
public record CompressOptions(EncoderProfile profile, int quality, int segmentSeconds, int concurrency,
                              AudioMode audio, MusicSettings music, boolean overlayTime) {

    public static CompressOptions defaults(EncoderProfile p) {
        return new CompressOptions(p, p.defaultQuality(), 0, p.defaultConcurrency(), AudioMode.KEEP,
                MusicSettings.DEFAULT, false);
    }

    public CompressOptions(EncoderProfile profile, int quality, int segmentSeconds, int concurrency, AudioMode audio) {
        this(profile, quality, segmentSeconds, concurrency, audio, MusicSettings.DEFAULT, false);
    }

    public CompressOptions(EncoderProfile profile, int quality, int segmentSeconds, int concurrency, AudioMode audio,
                           MusicSettings music) {
        this(profile, quality, segmentSeconds, concurrency, audio, music, false);
    }

    public CompressOptions withAudio(AudioMode mode, MusicSettings m) {
        return new CompressOptions(profile, quality, segmentSeconds, concurrency, mode, m, overlayTime);
    }

    public CompressOptions {
        audio = audio == null ? AudioMode.KEEP : audio;
        music = music == null ? MusicSettings.DEFAULT : music;
        concurrency = Math.max(1, Math.min(4, concurrency));
        // the UI offers 30 s - 10 min; shorter values are allowed for tests and tiny inputs
        segmentSeconds = segmentSeconds <= 0 ? 0 : Math.max(2, Math.min(600, segmentSeconds));
    }
}
