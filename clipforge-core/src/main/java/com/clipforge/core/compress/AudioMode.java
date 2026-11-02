package com.clipforge.core.compress;

/** What happens to the audio of a compressed video. */
public enum AudioMode {
    /** Copy the original audio track (re-encoded to AAC only if it cannot be copied into MP4). */
    KEEP,
    /** No audio track in the output. */
    REMOVE
}
