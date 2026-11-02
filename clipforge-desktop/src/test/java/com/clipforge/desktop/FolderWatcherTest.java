package com.clipforge.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FolderWatcherTest {

    @TempDir
    Path dir;

    @Test
    void newClipsTriggerOneRescanAfterTheFolderIsQuiet() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch called = new CountDownLatch(1);
        try (FolderWatcher w = new FolderWatcher(() -> {
            calls.incrementAndGet();
            called.countDown();
        }, 400)) {
            w.watch(List.of(dir));
            Files.writeString(dir.resolve("notes.txt"), "ignored"); // not a video
            for (int i = 0; i < 5; i++) {
                Files.write(dir.resolve("NO20261101-10000" + i + "-00000" + i + "F.mp4"), new byte[100]);
                Thread.sleep(50);
            }
            assertTrue(called.await(10, TimeUnit.SECONDS), "rescan requested");
            Thread.sleep(800);
            assertEquals(1, calls.get(), "events are coalesced");
        }
    }
}