package com.clipforge.core.project;

import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.audio.MusicSettings;
import com.clipforge.core.compress.CompressOptions;
import com.clipforge.core.merge.MergeMode;
import com.clipforge.core.merge.MergeOptions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Content of a {@code .cfproj} file. Mutable; owned by one controller thread at a time. */
public final class Project {

    public static final int FORMAT_VERSION = 1;

    private int formatVersion = FORMAT_VERSION;
    private List<String> folders = new ArrayList<>();
    private Set<String> selectedClips = new LinkedHashSet<>();
    private Set<String> seenClips = new LinkedHashSet<>();
    private MergeMode mergeMode = MergeMode.PER_SESSION;
    private MergeOptions mergeOptions = MergeOptions.DEFAULT;
    private String outputDir;
    private CompressOptions compressOptions;
    private boolean compressFromFile;
    private String compressFile;
    private AudioMode audioMode = AudioMode.KEEP;
    private MusicSettings music = MusicSettings.DEFAULT;
    private List<JobRecord> jobs = new ArrayList<>();
    /** Steps applied by one run: any combination of merge, speed-up and compression. */
    private boolean stepMerge = true;
    private boolean stepSpeed;
    private boolean stepCompress;
    private double speedFactor = 2;
    /** Ask how to delete the sources when starting (the choice itself is made in a dialog, per start). */
    private boolean deleteSources;
    /** The user's order of clips for a free merge (absolute paths). */
    private List<String> mergeOrder = new ArrayList<>();
    /** "Hide plates and faces" step, and what it hides. */
    private boolean stepHide;
    private boolean hidePlates = true;
    private boolean hideFaces = true;

    public boolean isStepHide() {
        return stepHide;
    }

    public void setStepHide(boolean stepHide) {
        this.stepHide = stepHide;
    }

    public boolean isHidePlates() {
        return hidePlates;
    }

    public void setHidePlates(boolean hidePlates) {
        this.hidePlates = hidePlates;
    }

    public boolean isHideFaces() {
        return hideFaces;
    }

    public void setHideFaces(boolean hideFaces) {
        this.hideFaces = hideFaces;
    }

    public List<String> getMergeOrder() {
        return mergeOrder;
    }

    public void setMergeOrder(List<String> mergeOrder) {
        this.mergeOrder = mergeOrder == null ? new ArrayList<>() : new ArrayList<>(mergeOrder);
    }

    public boolean isDeleteSources() {
        return deleteSources;
    }

    public void setDeleteSources(boolean deleteSources) {
        this.deleteSources = deleteSources;
    }

    public boolean isStepMerge() {
        return stepMerge;
    }

    public void setStepMerge(boolean stepMerge) {
        this.stepMerge = stepMerge;
    }

    public boolean isStepSpeed() {
        return stepSpeed;
    }

    public void setStepSpeed(boolean stepSpeed) {
        this.stepSpeed = stepSpeed;
    }

    public boolean isStepCompress() {
        return stepCompress;
    }

    public void setStepCompress(boolean stepCompress) {
        this.stepCompress = stepCompress;
    }

    public double getSpeedFactor() {
        return speedFactor;
    }

    public void setSpeedFactor(double speedFactor) {
        this.speedFactor = speedFactor >= CompressOptions.MIN_SPEED
                ? Math.min(CompressOptions.MAX_SPEED, speedFactor) : 2;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public List<String> getFolders() {
        return folders;
    }

    public void setFolders(List<String> folders) {
        this.folders = folders == null ? new ArrayList<>() : new ArrayList<>(folders);
    }

    public Set<String> getSelectedClips() {
        return selectedClips;
    }

    public void setSelectedClips(Set<String> selectedClips) {
        this.selectedClips = selectedClips == null ? new LinkedHashSet<>() : new LinkedHashSet<>(selectedClips);
    }

    /** Every clip path the project has seen; new clips (not in this set) are selected automatically. */
    public Set<String> getSeenClips() {
        return seenClips;
    }

    public void setSeenClips(Set<String> seenClips) {
        this.seenClips = seenClips == null ? new LinkedHashSet<>() : new LinkedHashSet<>(seenClips);
    }

    public MergeMode getMergeMode() {
        return mergeMode;
    }

    public void setMergeMode(MergeMode mergeMode) {
        this.mergeMode = mergeMode == null ? MergeMode.PER_SESSION : mergeMode;
    }

    public MergeOptions getMergeOptions() {
        return mergeOptions;
    }

    public void setMergeOptions(MergeOptions mergeOptions) {
        this.mergeOptions = mergeOptions == null ? MergeOptions.DEFAULT : mergeOptions;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    /** Last used compression settings; {@code null} until the user picked an encoder. */
    public CompressOptions getCompressOptions() {
        return compressOptions;
    }

    public void setCompressOptions(CompressOptions compressOptions) {
        this.compressOptions = compressOptions;
    }

    public boolean isCompressFromFile() {
        return compressFromFile;
    }

    public void setCompressFromFile(boolean compressFromFile) {
        this.compressFromFile = compressFromFile;
    }

    public String getCompressFile() {
        return compressFile;
    }

    public void setCompressFile(String compressFile) {
        this.compressFile = compressFile;
    }

    /** Audio handling for new merge and compression jobs. */
    public AudioMode getAudioMode() {
        return audioMode;
    }

    public void setAudioMode(AudioMode audioMode) {
        this.audioMode = audioMode == null ? AudioMode.KEEP : audioMode;
    }

    public MusicSettings getMusic() {
        return music;
    }

    public void setMusic(MusicSettings music) {
        this.music = music == null ? MusicSettings.DEFAULT : music;
    }

    public List<JobRecord> getJobs() {
        return jobs;
    }

    public void setJobs(List<JobRecord> jobs) {
        this.jobs = jobs == null ? new ArrayList<>() : new ArrayList<>(jobs);
    }

    public boolean hasUnfinishedJobs() {
        return jobs.stream().anyMatch(j -> j.getState().unfinished());
    }
}
