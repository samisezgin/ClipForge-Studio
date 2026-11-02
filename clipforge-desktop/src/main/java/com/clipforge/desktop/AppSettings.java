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

    /** Folders searched for extra {@code messages_*.properties} files. */
    public static List<Path> languageDirs() {
        List<Path> dirs = new ArrayList<>();
        dirs.add(Path.of(System.getProperty("user.dir"), "lang"));
        dirs.add(homeDir().resolve("lang"));
        return dirs;
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
