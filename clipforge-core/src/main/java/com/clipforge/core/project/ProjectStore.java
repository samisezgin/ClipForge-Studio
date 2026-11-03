package com.clipforge.core.project;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Reads and writes {@code .cfproj} files (JSON). Saving is crash safe: the content is written and flushed to a
 * temporary file next to the target, which is then atomically moved over the old file.
 */
public final class ProjectStore {

    public static final String EXTENSION = "cfproj";

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // a mode added by a newer version must not make the whole project unreadable
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);

    private ProjectStore() {
    }

    public static Project load(Path file) throws IOException {
        Project p = JSON.readValue(file.toFile(), Project.class);
        if (p.getFormatVersion() > Project.FORMAT_VERSION) {
            throw new IOException("Project was saved by a newer ClipForge version (format "
                    + p.getFormatVersion() + ")");
        }
        return p;
    }

    public static synchronized void save(Project project, Path file) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        byte[] bytes = JSON.writeValueAsBytes(project);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(ByteBuffer.wrap(bytes));
            ch.force(true);
        }
        // antivirus, indexers or sync clients can briefly lock the target on Windows: retry a few times
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(100L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    /** Adds the {@code .cfproj} extension if missing. */
    public static Path withExtension(Path file) {
        String n = file.getFileName().toString();
        return n.toLowerCase(Locale.ROOT).endsWith("." + EXTENSION) ? file : file.resolveSibling(n + "." + EXTENSION);
    }
}
