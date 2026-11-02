package com.clipforge.core.compress;

/**
 * @param profile        encoder preset
 * @param quality        CQ/CRF value (lower = better quality, bigger file)
 * @param segmentSeconds segment length; 0 = automatic (about one minute of encoding work per segment)
 * @param concurrency    segments encoded at the same time
 * @param audio          audio handling
 */
public record CompressOptions(EncoderProfile profile, int quality, int segmentSeconds, int concurrency,
                              AudioMode audio) {

    public static CompressOptions defaults(EncoderProfile p) {
        return new CompressOptions(p, p.defaultQuality(), 0, p.defaultConcurrency(), AudioMode.KEEP);
    }

    public CompressOptions {
        concurrency = Math.max(1, Math.min(4, concurrency));
        // the UI offers 30 s - 10 min; shorter values are allowed for tests and tiny inputs
        segmentSeconds = segmentSeconds <= 0 ? 0 : Math.max(2, Math.min(600, segmentSeconds));
    }
}
