package com.clipforge.core.project;

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
    private List<JobRecord> jobs = new ArrayList<>();

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
