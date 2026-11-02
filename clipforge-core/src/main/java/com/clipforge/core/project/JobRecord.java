package com.clipforge.core.project;

import com.clipforge.core.compress.CompressSpec;
import com.clipforge.core.compress.CompressState;
import com.clipforge.core.merge.MergeSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent state of one job in the project file. Mutable: the job runner updates it and the project is saved
 * after every state change.
 */
public final class JobRecord {

    private String id;
    private JobType type;
    private JobState state;
    private MergeSpec merge;
    private CompressState compress;
    private List<Fingerprint> sources = new ArrayList<>();
    private Instant created;
    private Instant updated;
    private String errorKey;
    private List<String> errorArgs = new ArrayList<>();
    private String errorDetail;

    public JobRecord() {
        // for Jackson
    }

    public static JobRecord merge(MergeSpec spec, List<Fingerprint> sources, Instant now) {
        JobRecord r = new JobRecord();
        r.id = UUID.randomUUID().toString();
        r.type = JobType.MERGE;
        r.state = JobState.PENDING;
        r.merge = spec;
        r.sources = new ArrayList<>(sources);
        r.created = now;
        r.updated = now;
        return r;
    }

    public static JobRecord compress(CompressSpec spec, List<Fingerprint> sources, Instant now) {
        JobRecord r = new JobRecord();
        r.id = UUID.randomUUID().toString();
        r.type = JobType.COMPRESS;
        r.state = JobState.PENDING;
        r.compress = new CompressState(spec);
        r.sources = new ArrayList<>(sources);
        r.created = now;
        r.updated = now;
        return r;
    }

    public CompressState getCompress() {
        return compress;
    }

    public void setCompress(CompressState compress) {
        this.compress = compress;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public JobType getType() {
        return type;
    }

    public void setType(JobType type) {
        this.type = type;
    }

    public JobState getState() {
        return state;
    }

    public void setState(JobState state) {
        this.state = state;
    }

    public MergeSpec getMerge() {
        return merge;
    }

    public void setMerge(MergeSpec merge) {
        this.merge = merge;
    }

    public List<Fingerprint> getSources() {
        return sources;
    }

    public void setSources(List<Fingerprint> sources) {
        this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
    }

    public Instant getCreated() {
        return created;
    }

    public void setCreated(Instant created) {
        this.created = created;
    }

    public Instant getUpdated() {
        return updated;
    }

    public void setUpdated(Instant updated) {
        this.updated = updated;
    }

    /** i18n key of the failure reason, e.g. {@code error.diskSpace}; {@code null} if none. */
    public String getErrorKey() {
        return errorKey;
    }

    public void setErrorKey(String errorKey) {
        this.errorKey = errorKey;
    }

    public List<String> getErrorArgs() {
        return errorArgs;
    }

    public void setErrorArgs(List<String> errorArgs) {
        this.errorArgs = errorArgs == null ? new ArrayList<>() : new ArrayList<>(errorArgs);
    }

    /** Technical detail (e.g. the end of FFmpeg's error output), not translated. */
    public String getErrorDetail() {
        return errorDetail;
    }

    public void setErrorDetail(String errorDetail) {
        this.errorDetail = errorDetail;
    }

    public void fail(String key, String detail, String... args) {
        this.state = JobState.FAILED;
        this.errorKey = key;
        this.errorArgs = new ArrayList<>(List.of(args));
        this.errorDetail = detail;
    }

    public void clearError() {
        this.errorKey = null;
        this.errorArgs = new ArrayList<>();
        this.errorDetail = null;
    }

    /** Output file of the job. */
    public String output() {
        if (merge != null) {
            return merge.output();
        }
        return compress != null && compress.getSpec() != null ? compress.getSpec().output() : null;
    }

    /** Total media duration the job processes, in milliseconds. */
    public long totalDurationMs() {
        if (merge != null) {
            return merge.totalDurationMs();
        }
        return compress != null && compress.getSpec() != null ? compress.getSpec().totalDurationMs() : 0;
    }

    /** Total size of the job's inputs. */
    public long totalBytes() {
        if (merge != null) {
            return merge.totalBytes();
        }
        return compress != null && compress.getSpec() != null ? compress.getSpec().totalBytes() : 0;
    }

    public int inputCount() {
        if (merge != null) {
            return merge.inputs().size();
        }
        return compress != null && compress.getSpec() != null ? compress.getSpec().inputs().size() : 0;
    }
}
