package com.clipforge.core.compress;

import java.util.ArrayList;
import java.util.List;

/**
 * Video encoder presets, modelled on HandBrake's quality-based presets (RF/CQ): a constant-quality value instead of
 * a bitrate, slow-ish presets, adaptive quantisation. Lower quality numbers mean better quality and larger files.
 * <p>
 * B-frames are disabled for the HEVC encoders so every independently encoded segment starts and ends with
 * monotonic decode times and can be appended byte-wise. AV1 has no container-level frame reordering.
 */
public enum EncoderProfile {

    // Defaults measured on noisy night dash cam footage (HEVC 2304x1296, 10 Mbps source, VMAF vs. source):
    //   AV1 NVENC CQ 36 -> 6.8 Mbps / VMAF 92, CQ 40 -> 4.3 Mbps / 88.7 (7.5x realtime on an RTX 4070 Ti)
    //   HEVC NVENC CQ 32 -> 6.4 Mbps / 90.7 (3.7x), SVT-AV1 p6 CRF 35 -> 3.8 Mbps / 90.6 (2x on a Ryzen 9 5950X)
    // Note the scales differ: av1_nvenc CQ is 0-63, hevc_nvenc 0-51, SVT-AV1 CRF 0-63, x265 CRF 0-51.
    // The last three numbers are the highest value of TRANSPARENT, VERY_GOOD and GOOD; above that is SMALL.
    AV1_NVENC("av1_nvenc", true, 24, 56, 38, 2, 150, 32, 36, 40),
    HEVC_NVENC("hevc_nvenc", true, 20, 42, 32, 2, 150, 26, 30, 34),
    SVT_AV1("libsvtav1", false, 20, 55, 35, 1, 45, 28, 32, 38),
    X265("libx265", false, 16, 36, 26, 1, 30, 20, 23, 28);

    private final String encoder;
    private final boolean hardware;
    private final int minQuality;
    private final int maxQuality;
    private final int defaultQuality;
    private final int defaultConcurrency;
    private final int defaultSegmentSeconds;
    private final int[] bandLimits;

    EncoderProfile(String encoder, boolean hardware, int minQuality, int maxQuality, int defaultQuality,
                   int defaultConcurrency, int defaultSegmentSeconds, int... bandLimits) {
        this.bandLimits = bandLimits;
        this.encoder = encoder;
        this.hardware = hardware;
        this.minQuality = minQuality;
        this.maxQuality = maxQuality;
        this.defaultQuality = defaultQuality;
        this.defaultConcurrency = defaultConcurrency;
        this.defaultSegmentSeconds = defaultSegmentSeconds;
    }

    /** FFmpeg encoder name. */
    public String encoder() {
        return encoder;
    }

    public boolean hardware() {
        return hardware;
    }

    public int minQuality() {
        return minQuality;
    }

    public int maxQuality() {
        return maxQuality;
    }

    public int defaultQuality() {
        return defaultQuality;
    }

    /** NVENC chips of recent GeForce cards run two sessions efficiently; CPU encoders already use all cores. */
    public int defaultConcurrency() {
        return defaultConcurrency;
    }

    /** Initial segment length before the speed-based automatic adjustment kicks in. */
    public int defaultSegmentSeconds() {
        return defaultSegmentSeconds;
    }

    /** The band a quality value falls into. */
    public QualityBand band(int quality) {
        for (int i = 0; i < bandLimits.length; i++) {
            if (quality <= bandLimits[i]) {
                return QualityBand.values()[i];
            }
        }
        return QualityBand.SMALL;
    }

    /** Inclusive value range of a band within the slider range, as {@code {from, to}}. */
    public int[] range(QualityBand band) {
        int i = band.ordinal();
        int from = i == 0 ? minQuality : bandLimits[i - 1] + 1;
        int to = i < bandLimits.length ? bandLimits[i] : maxQuality;
        return new int[] {from, to};
    }

    /** Short codec tag used in output names, e.g. {@code av1}. */
    public String shortName() {
        return this == AV1_NVENC || this == SVT_AV1 ? "av1" : "hevc";
    }

    /** Encoder arguments (after {@code -c:v}). */
    public List<String> videoArgs(int quality) {
        int q = Math.max(minQuality, Math.min(maxQuality, quality));
        List<String> a = new ArrayList<>(List.of("-c:v", encoder));
        switch (this) {
            case AV1_NVENC -> a.addAll(List.of("-preset", "p6", "-tune", "hq", "-rc", "vbr", "-cq", str(q),
                    "-b:v", "0", "-multipass", "qres", "-spatial-aq", "1", "-temporal-aq", "1",
                    "-rc-lookahead", "32", "-g", "120"));
            case HEVC_NVENC -> a.addAll(List.of("-preset", "p6", "-tune", "hq", "-rc", "vbr", "-cq", str(q),
                    "-b:v", "0", "-multipass", "qres", "-spatial-aq", "1", "-temporal-aq", "1",
                    "-rc-lookahead", "32", "-bf", "0", "-g", "120", "-tag:v", "hvc1"));
            case SVT_AV1 -> a.addAll(List.of("-preset", "6", "-crf", str(q), "-g", "240",
                    "-svtav1-params", "log-level=1"));
            case X265 -> a.addAll(List.of("-preset", "medium", "-crf", str(q),
                    "-x265-params", "bframes=0:keyint=120:log-level=error", "-tag:v", "hvc1"));
        }
        return a;
    }

    private static String str(int v) {
        return Integer.toString(v);
    }
}
