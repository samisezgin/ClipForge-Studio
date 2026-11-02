package com.clipforge.desktop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;

/** Per-user application settings (not project data), stored with {@link Preferences}. */
public final class AppSettings {

    private static final int MAX_RECENT = 8;
    private static final String SEP = "\n";

    private final Preferences prefs;

    public AppSettings(Preferences prefs) {
        this.prefs = prefs;
    }

    public static AppSettings user() {
        return new AppSettings(Preferences.userRoot().node("com/clipforge/studio"));
    }

    /**
     * ~/.clipforge — autosave location for untitled projects and user language files.
     * Can be overridden with the {@code clipforge.home} system property (used by tests).
     */
    public static Path homeDir() {
        String override = System.getProperty("clipforge.home");
        return override != null ? Path.of(override) : Path.of(System.getProperty("user.home"), ".clipforge");
    }

    /**
     * Folders searched for extra {@code messages_*.properties} files: {@code lang/} next to the application
     * (not the current working directory) and {@code ~/.clipforge/lang}.
     */
    public static List<Path> languageDirs() {
        List<Path> dirs = new ArrayList<>();
        appDir().ifPresent(d -> dirs.add(d.resolve("lang")));
        dirs.add(homeDir().resolve("lang"));
        return dirs;
    }

    /** Directory containing the application jar (or the classes folder when run from sources). */
    static java.util.Optional<Path> appDir() {
        try {
            var src = AppSettings.class.getProtectionDomain().getCodeSource();
            if (src == null) {
                return java.util.Optional.empty();
            }
            Path p = Path.of(src.getLocation().toURI());
            return java.util.Optional.ofNullable(p.getParent());
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    public String language() {
        return prefs.get("language", "");
    }

    public void setLanguage(String tag) {
        prefs.put("language", tag);
    }

    public boolean darkTheme() {
        return prefs.getBoolean("darkTheme", true);
    }

    public void setDarkTheme(boolean dark) {
        prefs.putBoolean("darkTheme", dark);
    }

    public String ffmpegDir() {
        return prefs.get("ffmpegDir", "");
    }

    public void setFfmpegDir(String dir) {
        prefs.put("ffmpegDir", dir == null ? "" : dir);
    }

    public String lastFolder() {
        return prefs.get("lastFolder", System.getProperty("user.home"));
    }

    public void setLastFolder(String dir) {
        prefs.put("lastFolder", dir);
    }

    public String lastOutputDir() {
        return prefs.get("lastOutputDir", "");
    }

    public void setLastOutputDir(String dir) {
        prefs.put("lastOutputDir", dir == null ? "" : dir);
    }

    /** Rescan the project folders automatically when clips appear (default on). */
    public boolean watchFolders() {
        return prefs.getBoolean("watchFolders", true);
    }

    public void setWatchFolders(boolean on) {
        prefs.putBoolean("watchFolders", on);
    }

    public String lastProject() {
        return prefs.get("lastProject", "");
    }

    public void setLastProject(String file) {
        prefs.put("lastProject", file == null ? "" : file);
    }

    public List<String> recentProjects() {
        String v = prefs.get("recentProjects", "");
        return v.isEmpty() ? List.of() : Arrays.asList(v.split(SEP));
    }

    public void removeRecentProject(String file) {
        List<String> list = new ArrayList<>(recentProjects());
        if (list.remove(file)) {
            prefs.put("recentProjects", String.join(SEP, list));
        }
    }

    public void addRecentProject(String file) {
        List<String> list = new ArrayList<>(recentProjects());
        list.remove(file);
        list.addFirst(file);
        while (list.size() > MAX_RECENT) {
            list.removeLast();
        }
        prefs.put("recentProjects", String.join(SEP, list));
    }
}
