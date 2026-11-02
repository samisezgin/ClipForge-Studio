package com.clipforge.core.merge;

import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.audio.MusicSettings;

/**
 * @param faststart write a standard MP4 with the index at the front (better for streaming/old players).
 *                  When {@code false} (default) a fragmented MP4 is written that grows while merging and
 *                  stays playable if interrupted.
 * @param audio     keep, remove or replace the audio
 * @param music     background music for {@link AudioMode#MUSIC}
 */
public record MergeOptions(boolean faststart, AudioMode audio, MusicSettings music) {

    public static final MergeOptions DEFAULT = new MergeOptions(false, AudioMode.KEEP, MusicSettings.DEFAULT);

    public MergeOptions {
        audio = audio == null ? AudioMode.KEEP : audio;
        music = music == null ? MusicSettings.DEFAULT : music;
    }

    public MergeOptions(boolean faststart) {
        this(faststart, AudioMode.KEEP, MusicSettings.DEFAULT);
    }

    public MergeOptions withAudio(AudioMode mode, MusicSettings m) {
        return new MergeOptions(faststart, mode, m);
    }

    public String extension() {
        return "mp4";
    }
}