package com.clipforge.core.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UI text lookup with pluggable languages.
 * <p>
 * Bundled languages are listed in {@code /i18n/languages.txt}. Additional {@code messages_<tag>.properties} files are
 * discovered at runtime in the configured extra directories (e.g. {@code lang/} next to the app and
 * {@code ~/.clipforge/lang}). Lookup order: selected language → its base language (e.g. {@code pt} for
 * {@code pt_BR}) → English. Files are UTF-8.
 */
public final class Messages {

    /** A selectable language. */
    public record Language(String tag, String displayName) {
        public Locale locale() {
            return Locale.forLanguageTag(tag.replace('_', '-'));
        }
    }

    private static final Logger LOG = Logger.getLogger(Messages.class.getName());
    private static final Pattern FILE = Pattern.compile("messages_([A-Za-z]{2,3}(?:_[A-Za-z0-9]{2,8})?)\\.properties");
    public static final String DEFAULT_TAG = "en";

    private static volatile Messages current = new Messages(DEFAULT_TAG, List.of());

    private final String tag;
    private final List<Path> extraDirs;
    private final List<Properties> chain;

    private Messages(String tag, List<Path> extraDirs) {
        this.tag = tag;
        this.extraDirs = List.copyOf(extraDirs);
        this.chain = buildChain(tag);
    }

    /** Selects the language (and extra lookup directories) used by {@link #get}. */
    public static void use(String tag, List<Path> extraDirs) {
        current = new Messages(tag == null || tag.isBlank() ? DEFAULT_TAG : tag, extraDirs);
    }

    public static Messages current() {
        return current;
    }

    public String tag() {
        return tag;
    }

    /**
     * Translated, {@link MessageFormat}-formatted text; returns {@code !key!} if the key is unknown.
     * All values are MessageFormat patterns, so a literal apostrophe must be written as {@code ''}.
     */
    public static String get(String key, Object... args) {
        return current.text(key, args);
    }

    public String text(String key, Object... args) {
        for (Properties p : chain) {
            String v = p.getProperty(key);
            if (v != null) {
                return new MessageFormat(v, locale()).format(args);
            }
        }
        return "!" + key + "!";
    }

    public Locale locale() {
        return Locale.forLanguageTag(tag.replace('_', '-'));
    }

    /** Bundled plus discovered languages, English first, then by display name. */
    public static List<Language> available(List<Path> extraDirs) {
        Map<String, Language> found = new LinkedHashMap<>();
        for (String t : bundledTags()) {
            Properties p = loadBundled(t);
            if (p != null) {
                found.put(t, new Language(t, p.getProperty("language.name", t)));
            }
        }
        for (Path dir : extraDirs) {
            for (Path f : listFiles(dir)) {
                Matcher m = FILE.matcher(f.getFileName().toString());
                if (m.matches()) {
                    Properties p = loadFile(f);
                    if (p != null) {
                        found.putIfAbsent(m.group(1), new Language(m.group(1), p.getProperty("language.name", m.group(1))));
                    }
                }
            }
        }
        List<Language> list = new ArrayList<>(found.values());
        list.sort(Comparator.comparing((Language l) -> !l.tag().equals(DEFAULT_TAG)).thenComparing(Language::displayName));
        return list;
    }

    /** Keys present in English but missing in the given language (useful for translators and tests). */
    public static List<String> missingKeys(String tag) {
        Properties en = loadBundled(DEFAULT_TAG);
        Properties other = loadBundled(tag);
        if (en == null || other == null) {
            return List.of();
        }
        return en.stringPropertyNames().stream().filter(k -> !other.containsKey(k)).sorted().toList();
    }

    private List<Properties> buildChain(String t) {
        List<Properties> result = new ArrayList<>();
        addLanguage(result, t);
        int sep = t.indexOf('_');
        if (sep > 0) {
            addLanguage(result, t.substring(0, sep));
        }
        if (!t.equals(DEFAULT_TAG)) {
            addLanguage(result, DEFAULT_TAG);
        }
        return result;
    }

    private void addLanguage(List<Properties> chain, String t) {
        for (Path dir : extraDirs) {
            Properties p = loadFile(dir.resolve("messages_" + t + ".properties"));
            if (p != null) {
                chain.add(p);
            }
        }
        Properties bundled = loadBundled(t);
        if (bundled != null) {
            chain.add(bundled);
        }
    }

    private static List<String> bundledTags() {
        List<String> tags = new ArrayList<>();
        try (InputStream in = Messages.class.getResourceAsStream("/i18n/languages.txt")) {
            if (in != null) {
                new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(tags::add);
            }
        } catch (IOException e) {
            LOG.warning("Cannot read languages.txt: " + e);
        }
        if (!tags.contains(DEFAULT_TAG)) {
            tags.addFirst(DEFAULT_TAG);
        }
        return tags;
    }

    private static Properties loadBundled(String t) {
        return t.equals(DEFAULT_TAG) ? loadResource("/i18n/messages.properties")
                : loadResource("/i18n/messages_" + t + ".properties");
    }

    private static Properties loadResource(String name) {
        try (InputStream in = Messages.class.getResourceAsStream(name)) {
            return in == null ? null : read(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static Properties loadFile(Path f) {
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(f)) {
            return read(in);
        } catch (IOException e) {
            LOG.warning("Cannot read " + f + ": " + e);
            return null;
        }
    }

    private static Properties read(InputStream in) throws IOException {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static List<Path> listFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "messages_*.properties")) {
            ds.forEach(files::add);
        } catch (IOException e) {
            LOG.warning("Cannot list " + dir + ": " + e);
        }
        return files;
    }

}
