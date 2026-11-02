package com.clipforge.core.audio;

/** What happens to the audio of a merged or compressed video. */
public enum AudioMode {
    /** Copy the original audio track (re-encoded to AAC only if it cannot be copied into MP4). */
    @com.fasterxml.jackson.annotation.JsonEnumDefaultValue
    KEEP,
    /** No audio track in the output (e.g. to drop cabin conversations). */
    REMOVE,
    /** Replace the audio with background music (optionally mixed with the original, quietly). */
    MUSIC
}
