package com.clipforge.core.mp4;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A 32-bit tfdt (written by some muxers) is rewritten as 64-bit with all enclosing sizes and offsets adjusted. */
class TfdtUpgradeTest {

    private static ByteBuffer moofWithV0Tfdt(int tfdt, int dataOffset) {
        // moof(8) + mfhd(16) + traf(8 + tfhd(16) + tfdt(16) + trun(28)) = 92 bytes
        ByteBuffer b = ByteBuffer.allocate(92);
        b.putInt(92).put("moof".getBytes());
        b.putInt(16).put("mfhd".getBytes()).putInt(0).putInt(7);
        b.putInt(68).put("traf".getBytes());
        b.putInt(16).put("tfhd".getBytes()).putInt(0x020000).putInt(1);
        b.putInt(16).put("tfdt".getBytes()).putInt(0).putInt(tfdt);
        b.putInt(28).put("trun".getBytes()).putInt(0x000301).putInt(1).putInt(dataOffset).putInt(3000).putInt(500);
        return b.flip();
    }

    @Test
    void upgradesAndShifts() throws Exception {
        ByteBuffer moof = moofWithV0Tfdt(1000, 96);
        long shift = 5_000_000_000L; // beyond 32 bits
        ByteBuffer out = FragmentedMp4.patchFragment(moof, new Mp4Box("moof", 0, 92, 8), 42, shift);

        assertEquals(96, out.remaining());
        assertEquals(96, out.getInt(0), "moof grew by 4");
        Mp4Box root = new Mp4Box("moof", 0, 96, 8);
        List<Mp4Box> children = Mp4Reader.children(out, root, 0);
        assertEquals(List.of("mfhd", "traf"), children.stream().map(Mp4Box::type).toList());
        assertEquals(42, out.getInt((int) children.get(0).payloadOffset() + 4), "sequence renumbered");
        Mp4Box traf = children.get(1);
        assertEquals(72, traf.size(), "traf grew by 4");
        List<Mp4Box> inner = Mp4Reader.children(out, traf, 0);
        Mp4Box tfdt = inner.get(1);
        assertEquals("tfdt", tfdt.type());
        assertEquals(20, tfdt.size());
        assertEquals(1, out.get((int) tfdt.payloadOffset()), "version 1");
        assertEquals(1000 + shift, out.getLong((int) tfdt.payloadOffset() + 4));
        Mp4Box trun = inner.get(2);
        assertEquals(100, out.getInt((int) trun.payloadOffset() + 8), "data offset still points at the samples");
    }
}
