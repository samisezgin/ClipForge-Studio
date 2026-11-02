package com.clipforge.core.mp4;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Inspects and concatenates single-track fragmented MP4 files ({@code ftyp, moov, (moof, mdat)*}) as written by
 * FFmpeg with {@code -movflags +frag_keyframe+empty_moov+default_base_moof}.
 * <p>
 * Appending copies only the {@code moof}/{@code mdat} pairs of a segment, renumbers {@code mfhd} sequence numbers and
 * shifts {@code tfdt} decode times so the segments play back-to-back. Because {@code default_base_moof} makes sample
 * offsets relative to each {@code moof}, the boxes can be copied without touching sample data.
 */
public final class FragmentedMp4 {

    /** The segment cannot be appended byte-wise to the target (different codec configuration or layout). */
    public static final class IncompatibleException extends IOException {
        public IncompatibleException(String message) {
            super(message);
        }
    }

    /**
     * @param init          bytes of {@code ftyp} + {@code moov}
     * @param sampleEntry   bytes of the {@code stsd} box (codec configuration) used for compatibility checks
     * @param timescale     media timescale ({@code mdhd})
     * @param trexDuration  default sample duration from {@code trex}, 0 if none
     * @param fragments     {@code moof} boxes, each followed by its {@code mdat}
     * @param durationTicks total media duration in {@code timescale} ticks
     * @param samples       number of samples
     */
    public record Info(byte[] init, byte[] sampleEntry, long timescale, long trexDuration, List<Fragment> fragments,
                       long durationTicks, long samples) {

        public boolean compatibleWith(Info other) {
            return timescale == other.timescale && Arrays.equals(sampleEntry, other.sampleEntry);
        }
    }

    /**
     * @param moof            the {@code moof} box
     * @param mdat            the following {@code mdat} box
     * @param decodeTime      {@code tfdt} value
     * @param durationTicks   sum of the fragment's sample durations
     */
    public record Fragment(Mp4Box moof, Mp4Box mdat, long decodeTime, long durationTicks, int samples) {
    }

    private FragmentedMp4() {
    }

    /** Reads the structure of a complete or truncated fragmented MP4; incomplete trailing fragments are ignored. */
    public static Info inspect(Path file) throws IOException {
        Mp4Reader.Scan scan = Mp4Reader.scan(file);
        Mp4Box ftyp = null;
        Mp4Box moov = null;
        List<Mp4Box> boxes = scan.boxes();
        for (Mp4Box b : boxes) {
            if (b.type().equals("ftyp") && ftyp == null) {
                ftyp = b;
            } else if (b.type().equals("moov") && moov == null) {
                moov = b;
            }
        }
        if (ftyp == null || moov == null) {
            throw new IOException("Not a fragmented MP4 (no ftyp/moov): " + file);
        }
        ByteBuffer moovBuf = Mp4Reader.read(file, moov);
        Mp4Box moovRel = new Mp4Box("moov", 0, moov.size(), moov.headerSize());
        List<Mp4Box> traks = Mp4Reader.children(moovBuf, moovRel, 0).stream().filter(b -> b.type().equals("trak")).toList();
        if (traks.size() != 1) {
            throw new IncompatibleException("Expected exactly one track, found " + traks.size());
        }
        Mp4Box mdhd = Mp4Reader.find(moovBuf, traks.getFirst(), "mdia", "mdhd")
                .orElseThrow(() -> new IOException("No mdhd"));
        long timescale = mdhdTimescale(moovBuf, mdhd);
        Mp4Box stsd = Mp4Reader.find(moovBuf, traks.getFirst(), "mdia", "minf", "stbl", "stsd")
                .orElseThrow(() -> new IOException("No stsd"));
        byte[] sampleEntry = new byte[(int) stsd.size()];
        moovBuf.get((int) stsd.offset(), sampleEntry);
        long trexDuration = Mp4Reader.find(moovBuf, moovRel, "mvex", "trex")
                .map(t -> Integer.toUnsignedLong(moovBuf.getInt((int) t.payloadOffset() + 12))).orElse(0L);

        byte[] init = new byte[(int) (ftyp.size() + moov.size())];
        ByteBuffer ftypBuf = Mp4Reader.read(file, ftyp);
        ftypBuf.get(init, 0, (int) ftyp.size());
        moovBuf.get(0, init, (int) ftyp.size(), (int) moov.size());

        List<Fragment> fragments = new ArrayList<>();
        long total = 0;
        long samples = 0;
        for (int i = 0; i < boxes.size(); i++) {
            Mp4Box b = boxes.get(i);
            if (!b.type().equals("moof")) {
                continue;
            }
            if (i + 1 >= boxes.size() || !boxes.get(i + 1).type().equals("mdat")) {
                break; // moof without its data: truncated
            }
            ByteBuffer moofBuf = Mp4Reader.read(file, b);
            Fragment f = parseFragment(moofBuf, b, boxes.get(i + 1), trexDuration);
            fragments.add(f);
            total += f.durationTicks();
            samples += f.samples();
        }
        return new Info(init, sampleEntry, timescale, trexDuration, List.copyOf(fragments), total, samples);
    }

    /** End of the last complete {@code moof+mdat} pair, i.e. where a truncated file should be cut. */
    public static long lastCompleteFragmentEnd(Info info) {
        if (info.fragments().isEmpty()) {
            return info.init().length;
        }
        return info.fragments().getLast().mdat().end();
    }

    /**
     * Decode time right after the last complete fragment, in timescale ticks.
     */
    public static long endTicks(Info info) {
        if (info.fragments().isEmpty()) {
            return 0;
        }
        Fragment last = info.fragments().getLast();
        return last.decodeTime() + last.durationTicks();
    }

    /** Result of an append: where the next segment continues. */
    public record AppendResult(long endTicks, int nextSequence, long fileSize) {
    }

    /**
     * Appends {@code segment} to {@code target}. If {@code target} is empty or missing, the segment's init boxes are
     * written first. The data is flushed to disk before returning.
     *
     * @param base         info of the first segment already in {@code target}, or {@code null} for the first append
     * @param startTicks   decode time the segment should start at (end of the previous segment)
     * @param nextSequence {@code mfhd} sequence number for the first fragment of the segment
     */
    public static AppendResult append(Path target, Info base, Path segment, long startTicks, int nextSequence)
            throws IOException {
        Info seg = inspect(segment);
        if (seg.fragments().isEmpty()) {
            throw new IOException("Segment has no fragments: " + segment);
        }
        if (base != null && !base.compatibleWith(seg)) {
            throw new IncompatibleException("Segment codec configuration differs from the first segment");
        }
        if (base != null && seg.trexDuration() != base.trexDuration() && reliesOnTrex(segment, seg)) {
            throw new IncompatibleException("Segment relies on different default sample durations");
        }
        long shift = startTicks - seg.fragments().getFirst().decodeTime();
        int seq = nextSequence;
        try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
             FileChannel in = FileChannel.open(segment, StandardOpenOption.READ)) {
            if (out.size() == 0) {
                writeFully(out, ByteBuffer.wrap(seg.init()));
            }
            for (Fragment f : seg.fragments()) {
                ByteBuffer moof = patchFragment(Mp4Reader.read(segment, f.moof()), f.moof(), seq++, shift);
                writeFully(out, moof);
                long copied = 0;
                while (copied < f.mdat().size()) {
                    long n = in.transferTo(f.mdat().offset() + copied, f.mdat().size() - copied, out);
                    if (n <= 0) {
                        throw new IOException("Short copy from " + segment);
                    }
                    copied += n;
                }
            }
            out.force(true);
            return new AppendResult(startTicks + seg.durationTicks(), seq, out.size());
        }
    }

    /** Cuts a file back to {@code size} bytes (removes a partially appended segment). */
    public static void truncate(Path file, long size) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            if (ch.size() > size) {
                ch.truncate(size);
                ch.force(true);
            }
        }
    }

    // ------------------------------------------------------------------ parsing helpers

    private static long mdhdTimescale(ByteBuffer buf, Mp4Box mdhd) {
        int p = (int) mdhd.payloadOffset();
        int version = buf.get(p) & 0xff;
        // version 1: creation(8) modification(8) timescale(4); version 0: creation(4) modification(4) timescale(4)
        return Integer.toUnsignedLong(buf.getInt(p + 4 + (version == 1 ? 16 : 8)));
    }

    private static Fragment parseFragment(ByteBuffer moof, Mp4Box moofAbs, Mp4Box mdat, long trexDuration)
            throws IOException {
        Mp4Box root = new Mp4Box("moof", 0, moofAbs.size(), moofAbs.headerSize());
        List<Mp4Box> trafs = Mp4Reader.children(moof, root, 0).stream().filter(b -> b.type().equals("traf")).toList();
        if (trafs.size() != 1) {
            throw new IncompatibleException("Expected one traf per moof, found " + trafs.size());
        }
        Mp4Box traf = trafs.getFirst();
        long defaultDuration = trexDuration;
        long decodeTime = 0;
        long duration = 0;
        int samples = 0;
        for (Mp4Box c : Mp4Reader.children(moof, traf, 0)) {
            int p = (int) c.payloadOffset();
            switch (c.type()) {
                case "tfhd" -> {
                    int flags = moof.getInt(p) & 0xffffff;
                    int q = p + 8; // version/flags + track_ID
                    if ((flags & 0x1) != 0) {
                        q += 8;
                    }
                    if ((flags & 0x2) != 0) {
                        q += 4;
                    }
                    if ((flags & 0x8) != 0) {
                        defaultDuration = Integer.toUnsignedLong(moof.getInt(q));
                    }
                }
                case "tfdt" -> {
                    int version = moof.get(p) & 0xff;
                    decodeTime = version == 1 ? moof.getLong(p + 4) : Integer.toUnsignedLong(moof.getInt(p + 4));
                }
                case "trun" -> {
                    int flags = moof.getInt(p) & 0xffffff;
                    int count = moof.getInt(p + 4);
                    int q = p + 8;
                    if ((flags & 0x1) != 0) {
                        q += 4; // data_offset
                    }
                    if ((flags & 0x4) != 0) {
                        q += 4; // first_sample_flags
                    }
                    int perSample = Integer.bitCount(flags & 0xf00) * 4;
                    for (int i = 0; i < count; i++) {
                        duration += (flags & 0x100) != 0 ? Integer.toUnsignedLong(moof.getInt(q)) : defaultDuration;
                        q += perSample;
                    }
                    samples += count;
                }
                default -> {
                    // other boxes (sdtp, ...) are copied unchanged
                }
            }
        }
        return new Fragment(moofAbs, mdat, decodeTime, duration, samples);
    }

    private static boolean reliesOnTrex(Path segment, Info seg) throws IOException {
        for (Fragment f : seg.fragments()) {
            ByteBuffer moof = Mp4Reader.read(segment, f.moof());
            Mp4Box root = new Mp4Box("moof", 0, f.moof().size(), f.moof().headerSize());
            Optional<Mp4Box> traf = Mp4Reader.children(moof, root, 0).stream()
                    .filter(b -> b.type().equals("traf")).findFirst();
            if (traf.isEmpty()) {
                continue;
            }
            boolean tfhdDefault = false;
            boolean trunDurations = false;
            for (Mp4Box c : Mp4Reader.children(moof, traf.get(), 0)) {
                int flags = moof.getInt((int) c.payloadOffset()) & 0xffffff;
                if (c.type().equals("tfhd")) {
                    tfhdDefault = (flags & 0x8) != 0;
                } else if (c.type().equals("trun")) {
                    trunDurations = (flags & 0x100) != 0;
                }
            }
            if (!tfhdDefault && !trunDurations) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renumbers mfhd and shifts tfdt in a moof held in {@code moof} (buffer offset 0 = box start). A 32-bit
     * (version 0) tfdt is rewritten as 64-bit, so outputs longer than 13.25 h (2^32 ticks at 90 kHz) work; the moof
     * and traf sizes and the trun data offset (relative to the moof with default-base-is-moof) grow by 4 bytes.
     */
    static ByteBuffer patchFragment(ByteBuffer moof, Mp4Box abs, int sequence, long shift) throws IOException {
        Mp4Box root = new Mp4Box("moof", 0, abs.size(), abs.headerSize());
        Mp4Box traf = null;
        Mp4Box tfdt = null;
        for (Mp4Box c : Mp4Reader.children(moof, root, 0)) {
            if (c.type().equals("mfhd")) {
                moof.putInt((int) c.payloadOffset() + 4, sequence);
            } else if (c.type().equals("traf")) {
                traf = c;
                for (Mp4Box t : Mp4Reader.children(moof, c, 0)) {
                    if (t.type().equals("tfdt")) {
                        tfdt = t;
                    }
                }
            }
        }
        if (traf == null || tfdt == null) {
            throw new IncompatibleException("Fragment without traf/tfdt");
        }
        int p = (int) tfdt.payloadOffset();
        int version = moof.get(p) & 0xff;
        if (version == 1) {
            moof.putLong(p + 4, moof.getLong(p + 4) + shift);
            return moof.rewind();
        }
        long value = Integer.toUnsignedLong(moof.getInt(p + 4)) + shift;
        int size = (int) abs.size();
        ByteBuffer out = ByteBuffer.allocate(size + 4);
        byte[] src = new byte[size];
        moof.get(0, src);
        int tfdtAt = (int) tfdt.offset();
        int tfdtEnd = (int) tfdt.end();
        out.put(src, 0, tfdtAt);
        out.putInt(20).put(new byte[]{'t', 'f', 'd', 't'}).put((byte) 1)
                .put(src[p + 1]).put(src[p + 2]).put(src[p + 3]).putLong(value);
        out.put(src, tfdtEnd, size - tfdtEnd);
        // grow the enclosing boxes
        growSize(out, 0, abs.headerSize());
        growSize(out, (int) traf.offset(), traf.headerSize());
        Mp4Box newTraf = new Mp4Box("traf", traf.offset(), traf.size() + 4, traf.headerSize());
        for (Mp4Box t : Mp4Reader.children(out, newTraf, 0)) {
            if (t.type().equals("trun")) {
                int q = (int) t.payloadOffset();
                int flags = out.getInt(q) & 0xffffff;
                if ((flags & 0x1) != 0) {
                    out.putInt(q + 8, out.getInt(q + 8) + 4); // data_offset is relative to the moof start
                }
            }
        }
        return out.rewind();
    }

    private static void growSize(ByteBuffer b, int at, int headerSize) {
        if (headerSize == 16) {
            b.putLong(at + 8, b.getLong(at + 8) + 4);
        } else {
            b.putInt(at, b.getInt(at) + 4);
        }
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }
}
