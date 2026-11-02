package com.clipforge.core.mp4;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Minimal ISO-BMFF (MP4) reader: enough to walk top-level boxes and inspect fragments. */
public final class Mp4Reader {

    private Mp4Reader() {
    }

    /** Result of scanning top-level boxes; {@code validEnd} is where the last complete box ends. */
    public record Scan(List<Mp4Box> boxes, long validEnd, long fileSize) {
        public boolean truncated() {
            return validEnd < fileSize;
        }
    }

    /** Lists complete top-level boxes; stops at the first truncated or malformed box. */
    public static Scan scan(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            List<Mp4Box> boxes = new ArrayList<>();
            long pos = 0;
            ByteBuffer head = ByteBuffer.allocate(16);
            while (pos + 8 <= size) {
                head.clear().limit(16);
                ch.read(head, pos);
                head.flip();
                if (head.remaining() < 8) {
                    break;
                }
                long boxSize = Integer.toUnsignedLong(head.getInt());
                String type = fourcc(head);
                int headerSize = 8;
                if (boxSize == 1) {
                    if (head.remaining() < 8) {
                        break;
                    }
                    boxSize = head.getLong();
                    headerSize = 16;
                } else if (boxSize == 0) {
                    boxSize = size - pos; // box extends to end of file
                }
                if (boxSize < headerSize || !printable(type) || boxSize > size - pos) { // overflow-safe
                    break;
                }
                boxes.add(new Mp4Box(type, pos, boxSize, headerSize));
                pos += boxSize;
            }
            return new Scan(boxes, pos, size);
        }
    }

    /** Reads a whole box (header included) into memory. */
    public static ByteBuffer read(Path file, Mp4Box box) throws IOException {
        if (box.size() > Integer.MAX_VALUE) {
            throw new IOException("Box too large to load: " + box);
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate((int) box.size());
            while (buf.hasRemaining()) {
                if (ch.read(buf, box.offset() + buf.position()) < 0) {
                    throw new IOException("Unexpected end of file in " + box);
                }
            }
            return buf.flip();
        }
    }

    /** Child boxes of a container box held in {@code buf} (offsets relative to the buffer start). */
    public static List<Mp4Box> children(ByteBuffer buf, Mp4Box parent, int extraHeader) {
        List<Mp4Box> list = new ArrayList<>();
        long pos = parent.payloadOffset() + extraHeader;
        while (pos + 8 <= parent.end()) {
            long size = Integer.toUnsignedLong(buf.getInt((int) pos));
            String type = fourcc(buf, (int) pos + 4);
            int header = 8;
            if (size == 1) {
                size = buf.getLong((int) pos + 8);
                header = 16;
            }
            if (size < header || size > parent.end() - pos) {
                break;
            }
            list.add(new Mp4Box(type, pos, size, header));
            pos += size;
        }
        return list;
    }

    /** Depth-first search for the first box of a type below {@code parent}. */
    public static Optional<Mp4Box> find(ByteBuffer buf, Mp4Box parent, String... path) {
        Mp4Box current = parent;
        for (String type : path) {
            Optional<Mp4Box> next = children(buf, current, containerExtra(current.type())).stream()
                    .filter(b -> b.type().equals(type)).findFirst();
            if (next.isEmpty()) {
                return Optional.empty();
            }
            current = next.get();
        }
        return Optional.of(current);
    }

    /** Full boxes that are containers carry 4 bytes of version/flags (and stsd an entry count) before children. */
    private static int containerExtra(String type) {
        return switch (type) {
            case "meta" -> 4;
            case "stsd" -> 8;
            default -> 0;
        };
    }

    static String fourcc(ByteBuffer b) {
        byte[] t = new byte[4];
        b.get(t);
        return new String(t, StandardCharsets.ISO_8859_1);
    }

    static String fourcc(ByteBuffer b, int at) {
        byte[] t = new byte[4];
        for (int i = 0; i < 4; i++) {
            t[i] = b.get(at + i);
        }
        return new String(t, StandardCharsets.ISO_8859_1);
    }

    private static boolean printable(String type) {
        for (char c : type.toCharArray()) {
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }
}
