package com.clipforge.core.scan;

import java.time.LocalDateTime;

/**
 * Information extracted from a file name.
 *
 * @param start    start time if the name contains one, otherwise {@code null}
 * @param channel  camera channel letter (e.g. {@code F}/{@code R}), otherwise {@code null}
 * @param sequence sequence/chapter number used for ordering, otherwise -1
 */
public record ParsedName(LocalDateTime start, String channel, long sequence) {

    public static final ParsedName EMPTY = new ParsedName(null, null, -1);
}
