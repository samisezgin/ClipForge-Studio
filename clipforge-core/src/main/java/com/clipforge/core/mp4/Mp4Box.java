package com.clipforge.core.mp4;

/**
 * Location of an ISO-BMFF box inside a file or buffer.
 *
 * @param type       four-character type, e.g. {@code moof}
 * @param offset     position of the box header
 * @param size       total size including header
 * @param headerSize 8, or 16 for 64-bit sizes
 */
public record Mp4Box(String type, long offset, long size, int headerSize) {

    public long end() {
        return offset + size;
    }

    public long payloadOffset() {
        return offset + headerSize;
    }
}
