package com.clipforge.core.components;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentManagerTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = files.get(ex.getRequestURI().getPath());
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    void installsAVerifiedFileAndAZipAndRejectsATamperedDownload() throws Exception {
        byte[] model = "model-bytes".getBytes(StandardCharsets.UTF_8);
        files.put("/m.onnx", model);
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zipBytes)) {
            for (String name : List.of("pkg/bin/ffmpeg.exe", "pkg/bin/ffprobe.exe", "pkg/doc/readme.txt")) {
                z.putNextEntry(new ZipEntry(name));
                z.write(name.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        files.put("/f.zip", zipBytes.toByteArray());

        Component file = new Component("m", "Model", "1", url("/m.onnx"), sha(model), model.length, "file",
                List.of("model.onnx"), "any", "always", "MIT");
        Component zip = new Component("f", "Tool", "2", url("/f.zip"), sha(zipBytes.toByteArray()),
                zipBytes.size(), "zip", List.of("ffmpeg.exe", "ffprobe.exe"), "any", "always", "GPL");
        Component bad = new Component("b", "Bad", "1", url("/m.onnx"), "00".repeat(32), model.length, "file",
                List.of("x.onnx"), "any", "always", "MIT");
        ComponentManager cm = new ComponentManager(tmp.resolve("components"), List.of(file, zip, bad));

        assertFalse(cm.isInstalled(file));
        long[] last = {0};
        cm.install(file, (done, total) -> last[0] = done, null);
        assertTrue(cm.isInstalled(file));
        assertEquals(model.length, last[0], "progress reaches the end");
        assertEquals("model-bytes", Files.readString(cm.file("m", "model.onnx").orElseThrow()));

        cm.install(zip, null, null);
        assertTrue(cm.isInstalled(zip));
        assertTrue(Files.isRegularFile(cm.dir(zip).resolve("ffprobe.exe")));
        assertFalse(Files.exists(cm.dir(zip).resolve("readme.txt")), "only the listed files");

        IOException e = assertThrows(IOException.class, () -> cm.install(bad, null, null));
        assertTrue(e.getMessage().contains("checksum"), e.getMessage());
        assertFalse(cm.isInstalled(bad));
        assertFalse(Files.exists(cm.dir(bad)), "nothing is left installed");
    }

    @Test
    void theBundledListHasPinnedDownloads() {
        List<Component> list = ComponentManager.bundled();
        assertEquals(List.of("ffmpeg", "plate-model", "face-model"), list.stream().map(Component::id).toList());
        for (Component c : list) {
            assertTrue(c.sha256().matches("[0-9a-f]{64}"), c.id());
            assertTrue(c.url().startsWith("https://"), c.id());
            assertTrue(c.size() > 0 && !c.files().isEmpty(), c.id());
        }
    }
}
