package com.clipforge.core.components;

import com.clipforge.core.ffmpeg.CancellationToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Checks and installs the components of {@code components.json} under {@code <home>/components/<id>/<version>/}.
 * A download goes to a temporary file, is checked against the listed SHA-256 and only then unpacked into place; a
 * marker file records the verified checksum, so a half-finished or tampered install is never used.
 */
public final class ComponentManager {

    private static final Logger LOG = Logger.getLogger(ComponentManager.class.getName());
    private static final String MARKER = ".installed";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;
    private final List<Component> components;

    public ComponentManager(Path root, List<Component> components) {
        this.root = root;
        this.components = List.copyOf(components);
    }

    /** The components listed in the bundled {@code components.json}. */
    public static List<Component> bundled() {
        try (InputStream in = ComponentManager.class.getResourceAsStream("components.json")) {
            if (in == null) {
                return List.of();
            }
            return JSON.readValue(in, new TypeReference<List<Component>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("components.json is unreadable", e);
        }
    }

    public List<Component> components() {
        return components;
    }

    public Optional<Component> find(String id) {
        return components.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public Path dir(Component c) {
        return root.resolve(c.id()).resolve(c.version());
    }

    /** Installed and verified: the marker holds the listed checksum and every file is there. */
    public boolean isInstalled(Component c) {
        Path dir = dir(c);
        try {
            if (!Files.isRegularFile(dir.resolve(MARKER))
                    || !Files.readString(dir.resolve(MARKER)).trim().equalsIgnoreCase(c.sha256())) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return c.files().stream().allMatch(f -> Files.isRegularFile(dir.resolve(f)));
    }

    /** Path of an installed file, if the component is installed. */
    public Optional<Path> file(String id, String fileName) {
        return find(id).filter(this::isInstalled).map(c -> dir(c).resolve(fileName));
    }

    /**
     * Downloads, verifies and installs one component.
     *
     * @param progress called with (bytes so far, total bytes)
     * @throws IOException on network errors, a wrong checksum or a broken archive; nothing is left installed then
     */
    public synchronized void install(Component c, BiConsumer<Long, Long> progress, CancellationToken token)
            throws IOException {
        if (isInstalled(c)) {
            return; // installed meanwhile by another caller (one install at a time)
        }
        Files.createDirectories(root.resolve(c.id()));
        Path download = root.resolve(c.id()).resolve(c.version() + ".download");
        Path staging = root.resolve(c.id()).resolve(c.version() + ".staging");
        try {
            String actual = fetch(c, download, progress, token);
            if (!actual.equalsIgnoreCase(c.sha256())) {
                throw new IOException(c.name() + ": checksum mismatch (expected " + c.sha256() + ", got " + actual
                        + "); the download was discarded");
            }
            deleteTree(staging);
            Files.createDirectories(staging);
            if (c.isZip()) {
                unzip(c, download, staging);
            } else {
                Files.move(download, staging.resolve(c.files().getFirst()), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(staging.resolve(MARKER), c.sha256());
            Path dir = dir(c);
            deleteTree(dir);
            Files.move(staging, dir);
            LOG.info("Installed " + c.name() + " in " + dir);
        } finally {
            Files.deleteIfExists(download);
            deleteTree(staging);
        }
    }

    private static String fetch(Component c, Path target, BiConsumer<Long, Long> progress, CancellationToken token)
            throws IOException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(c.url())).header("User-Agent", "ClipForge-Studio")
                .GET().build();
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IOException(c.name() + ": HTTP " + resp.statusCode() + " from " + c.url());
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        long total = c.size() > 0 ? c.size() : resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = new DigestInputStream(resp.body(), sha); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            long reported = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (token != null && token.isCancelled()) {
                    throw new IOException("Download cancelled");
                }
                out.write(buf, 0, n);
                done += n;
                if (progress != null && (done - reported > (1 << 20) || done == total)) {
                    progress.accept(done, total);
                    reported = done;
                }
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /** Takes the listed files out of the archive (wherever they are inside it) into {@code dir}. */
    private static void unzip(Component c, Path zip, Path dir) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName().replace('\\', '/');
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (c.files().contains(base)) {
                    Files.copy(in, dir.resolve(base), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        for (String f : c.files()) {
            if (!Files.isRegularFile(dir.resolve(f))) {
                throw new IOException(c.name() + ": " + f + " is not in the archive");
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(x);
            }
        }
    }
}
