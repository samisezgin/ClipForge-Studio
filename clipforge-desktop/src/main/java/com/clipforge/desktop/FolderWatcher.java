package com.clipforge.desktop;

import com.clipforge.core.scan.ClipScanner;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Watches the project folders and reports when video files appear or change (e.g. while clips are still being copied
 * from an SD card). Events are coalesced: the callback runs once the folder has been quiet for {@link #QUIET_MILLIS},
 * so a long copy triggers a few rescans instead of hundreds.
 */
public final class FolderWatcher implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(FolderWatcher.class.getName());
    /** Longer than the scanner's "still being copied" threshold, so a rescan sees finished files as ready. */
    static final long QUIET_MILLIS = 35_000;

    private final Runnable onChange;
    private WatchService service;
    private Thread thread;

    private final long quietMillis;

    public FolderWatcher(Runnable onChange) {
        this(onChange, QUIET_MILLIS);
    }

    FolderWatcher(Runnable onChange, long quietMillis) {
        this.onChange = onChange;
        this.quietMillis = quietMillis;
    }

    /** (Re)starts watching exactly these folders. */
    public synchronized void watch(List<Path> folders) {
        stop();
        if (folders.isEmpty()) {
            return;
        }
        try {
            WatchService ws = FileSystems.getDefault().newWatchService();
            for (Path f : folders) {
                try {
                    f.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                } catch (IOException e) {
                    LOG.warning("Cannot watch " + f + ": " + e.getMessage());
                }
            }
            service = ws;
            thread = Thread.ofPlatform().daemon(true).name("clipforge-watch").start(() -> loop(ws));
        } catch (IOException e) {
            LOG.warning("Folder watching unavailable: " + e.getMessage());
        }
    }

    private void loop(WatchService ws) {
        boolean pending = false;
        long pendingSince = 0;
        try {
            while (true) {
                WatchKey key = pending ? ws.poll(quietMillis, TimeUnit.MILLISECONDS) : ws.take();
                // quiet long enough, or a copy that never pauses: rescan at least every few minutes
                boolean overdue = pending && System.currentTimeMillis() - pendingSince > 4 * quietMillis;
                if (key == null || overdue) {
                    pending = false;
                    onChange.run();
                    if (key == null) {
                        continue;
                    }
                }
                for (WatchEvent<?> e : key.pollEvents()) {
                    if (e.context() instanceof Path p && ClipScanner.isVideo(p) && !pending) {
                        pending = true;
                        pendingSince = System.currentTimeMillis();
                    }
                }
                key.reset();
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // stopped
        }
    }

    private void stop() {
        if (service != null) {
            try {
                service.close();
            } catch (IOException ignored) {
                // closing anyway
            }
            service = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    @Override
    public synchronized void close() {
        stop();
    }
}
