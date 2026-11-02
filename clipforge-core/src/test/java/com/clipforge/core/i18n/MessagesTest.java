package com.clipforge.core.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    @TempDir
    Path langDir;

    @AfterEach
    void reset() {
        Messages.use(Messages.DEFAULT_TAG, List.of());
    }

    @Test
    void englishAndTurkishAreBundled() {
        List<String> tags = Messages.available(List.of()).stream().map(Messages.Language::tag).toList();
        assertEquals("en", tags.getFirst());
        assertTrue(tags.contains("tr"));
    }

    @Test
    void turkishHasEveryEnglishKey() {
        assertEquals(List.of(), Messages.missingKeys("tr"));
    }

    @Test
    void formatsPlaceholdersAndChoices() {
        Messages.use("en", List.of());
        assertEquals("Session · 1 clip", Messages.get("clips.session", 1));
        assertEquals("Session · 3 clips", Messages.get("clips.session", 3));
        Messages.use("tr", List.of());
        assertTrue(Messages.get("error.fat32", "FAT32").contains("4 GB'tan"), "'' becomes an apostrophe");
    }

    @Test
    void unknownKeyIsVisible() {
        assertEquals("!no.such.key!", Messages.get("no.such.key"));
    }

    @Test
    void externalLanguageFileIsDiscoveredAndFallsBackToEnglish() throws Exception {
        Files.writeString(langDir.resolve("messages_de.properties"),
                "language.name=Deutsch\nmenu.file=Datei\n", StandardCharsets.UTF_8);
        assertTrue(Messages.available(List.of(langDir)).stream()
                .anyMatch(l -> l.tag().equals("de") && l.displayName().equals("Deutsch")));
        Messages.use("de", List.of(langDir));
        assertEquals("Datei", Messages.get("menu.file"));
        assertEquals("Merge", Messages.get("merge.title"), "missing keys fall back to English");
    }

    @Test
    void regionalVariantFallsBackToBaseLanguage() throws Exception {
        Files.writeString(langDir.resolve("messages_tr_CY.properties"), "menu.file=DOSYA\n", StandardCharsets.UTF_8);
        Messages.use("tr_CY", List.of(langDir));
        assertEquals("DOSYA", Messages.get("menu.file"));
        assertEquals("Görünüm", Messages.get("menu.view"));
    }
}
