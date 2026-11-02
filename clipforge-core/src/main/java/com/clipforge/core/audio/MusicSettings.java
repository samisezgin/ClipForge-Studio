package com.clipforge.core.audio;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Background music for {@link AudioMode#MUSIC}.
 *
 * @param tracks           music files, played in this order (a playlist)
 * @param loop             repeat the playlist until the video ends; otherwise silence after the last track
 * @param crossfadeSeconds overlap between consecutive tracks and at every loop restart (0 = hard cut)
 * @param fadeOut          fade the music out at the end of the video
 * @param fadeOutSeconds   length of the fade-out
 * @param volume           music volume (1.0 = unchanged)
 * @param mixOriginal      keep the original audio quietly under the music
 * @param originalVolume   volume of the original audio when mixed
 */
public record MusicSettings(List<String> tracks, boolean loop, double crossfadeSeconds, boolean fadeOut,
                            double fadeOutSeconds, double volume, boolean mixOriginal, double originalVolume) {

    public static final double MIN_FADE = 0.5;
    public static final double MAX_FADE = 30;
    public static final double MAX_CROSSFADE = 3;

    public static final MusicSettings DEFAULT = new MusicSettings(List.of(), true, 1.0, true, 3.0, 1.0, false, 0.2);

    public MusicSettings {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
        crossfadeSeconds = clamp(crossfadeSeconds, 0, MAX_CROSSFADE);
        fadeOutSeconds = clamp(fadeOutSeconds <= 0 ? 3 : fadeOutSeconds, MIN_FADE, MAX_FADE);
        volume = clamp(volume, 0, 2);
        originalVolume = clamp(originalVolume, 0, 1);
    }

    /** JSON: missing fields fall back to {@link #DEFAULT} (a partial object must not mean volume 0 / no loop). */
    @JsonCreator
    static MusicSettings fromJson(@JsonProperty("tracks") List<String> tracks,
                                  @JsonProperty("loop") Boolean loop,
                                  @JsonProperty("crossfadeSeconds") Double crossfade,
                                  @JsonProperty("fadeOut") Boolean fadeOut,
                                  @JsonProperty("fadeOutSeconds") Double fadeSeconds,
                                  @JsonProperty("volume") Double volume,
                                  @JsonProperty("mixOriginal") Boolean mix,
                                  @JsonProperty("originalVolume") Double originalVolume) {
        MusicSettings d = DEFAULT;
        return new MusicSettings(tracks, loop == null ? d.loop() : loop,
                crossfade == null ? d.crossfadeSeconds() : crossfade, fadeOut == null ? d.fadeOut() : fadeOut,
                fadeSeconds == null ? d.fadeOutSeconds() : fadeSeconds, volume == null ? d.volume() : volume,
                mix == null ? d.mixOriginal() : mix, originalVolume == null ? d.originalVolume() : originalVolume);
    }

    public MusicSettings withTracks(List<String> t) {
        return new MusicSettings(t, loop, crossfadeSeconds, fadeOut, fadeOutSeconds, volume, mixOriginal, originalVolume);
    }

    private static double clamp(double v, double min, double max) {
        return Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }
}
