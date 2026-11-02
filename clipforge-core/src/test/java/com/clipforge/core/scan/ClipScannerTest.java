package com.clipforge.core.scan;

import com.clipforge.core.TestClips;
import com.clipforge.core.model.Clip;
import com.clipforge.core.model.ClipStatus;
import com.clipforge.core.util.DiskCheckTestAccess;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipScannerTest {

    @Test
    void sameRecordingInTwoFoldersIsMarkedDuplicate() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 1, 10, 0);
        Clip a = TestClips.clip("card/NO20261101-100000-000001F.mp4", t, Duration.ofMinutes(2), "F");
        Clip b = TestClips.clip("backup/NO20261101-100000-000001F.mp4", t, Duration.ofMinutes(2), "F");
        List<Clip> result = ClipScanner.markDuplicates(List.of(a, b));
        assertEquals(ClipStatus.READY, result.get(0).status());
        assertEquals(ClipStatus.DUPLICATE, result.get(1).status());
        assertFalse(result.get(1).selectable());
    }

    @Test
    void fatDetection() {
        assertTrue(DiskCheckTestAccess.isFat("FAT32"));
        assertTrue(DiskCheckTestAccess.isFat("vfat"));
        assertTrue(DiskCheckTestAccess.isFat("msdos"));
        assertFalse(DiskCheckTestAccess.isFat("exFAT"));
        assertFalse(DiskCheckTestAccess.isFat("NTFS"));
    }
}
