package com.clipforge.core.audio;

import com.clipforge.core.ffmpeg.CancellationToken;
import com.clipforge.core.ffmpeg.FfmpegProcess;
import com.clipforge.core.ffmpeg.FfmpegResult;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.ProbeService;
import com.clipforge.core.model.MediaInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Renders the soundtrack for {@link AudioMode#MUSIC}: an AAC track exactly as long as the video.
 * <ol>
 *     <li>The playlist is decoded into one block (48 kHz stereo), with crossfades between consecutive tracks.</li>
 *     <li>For looping, a seamless loop unit is built once: the block's tail crossfaded into its head, followed by the
 *     block's body. Playing {@code head-part + unit + unit + ...} equals the block repeated with a crossfade at every
 *     restart, for any video length, with constant memory ({@code -stream_loop -1}).</li>
 *     <li>The result is trimmed to the video length, its volume set, faded out and optionally mixed with the
 *     original audio. Without looping, silence follows the last track.</li>
 * </ol>
 * Filter graphs are passed as files ({@code -/filter_complex}) to stay clear of the Windows command-line limit.
 */
public final class MusicRenderer {

    /** A music file could not be read; {@link #track()} names it. */
    public static final class TrackException extends IOException {
        private final String track;

        public TrackException(String track) {
            super("Unreadable music file: " + track);
            this.track = track;
        }

        public String track() {
            return track;
        }
    }

    private static final String NORMALIZE = "aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo";

    private final FfmpegTools tools;

    public MusicRenderer(FfmpegTools tools) {
        this.tools = tools;
    }

    /** Durations of the tracks in seconds; throws for the first unreadable file. */
    public List<Double> durations(List<String> tracks) throws TrackException {
        ProbeService probe = new ProbeService(tools);
        List<Double> d = new ArrayList<>();
        for (String t : tracks) {
            Optional<MediaInfo> m = Files.isRegularFile(Path.of(t)) ? probe.probe(Path.of(t)) : Optional.empty();
            if (m.isEmpty() || !m.get().hasAudio()) {
                throw new TrackException(Path.of(t).getFileName().toString());
            }
            d.add(m.get().duration().toMillis() / 1000.0);
        }
        return d;
    }

    /**
     * @param videoSeconds length of the video the music is for
     * @param original     original audio to mix in (if {@link MusicSettings#mixOriginal()}), may be {@code null}
     * @param work         folder for intermediate files (they are deleted afterwards)
     * @param out          AAC/MP4 file to write
     */
    public FfmpegResult render(MusicSettings s, double videoSeconds, Path original, Path work, Path out,
                               CancellationToken token) throws IOException {
        if (s.tracks().isEmpty()) {
            throw new IOException("No music tracks");
        }
        Files.createDirectories(work);
        List<Double> durations = durations(s.tracks());
        double shortest = durations.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        // a crossfade can never be longer than half of the shortest track
        double xf = Math.max(0, Math.min(s.crossfadeSeconds(), shortest / 2 - 0.05));
        double blockSeconds = durations.stream().mapToDouble(Double::doubleValue).sum() - (durations.size() - 1) * xf;

        Path block = work.resolve("music_block.wav");
        Path unit = work.resolve("music_unit.wav");
        try {
            FfmpegResult r = renderBlock(s.tracks(), xf, block, work, token);
            if (!r.success()) {
                return r;
            }
            boolean loop = s.loop() && blockSeconds < videoSeconds;
            double loopXf = loop && blockSeconds > 2 * xf + 0.2 ? xf : 0;
            if (loop && loopXf > 0) {
                r = renderUnit(block, blockSeconds, loopXf, unit, work, token);
                if (!r.success()) {
                    return r;
                }
            }
            return renderFinal(s, videoSeconds, block, blockSeconds, loop, loopXf, unit, original, work, out, token);
        } finally {
            Files.deleteIfExists(block);
            Files.deleteIfExists(unit);
            for (String n : List.of("music_block.txt", "music_unit.txt", "music_final.txt")) {
                Files.deleteIfExists(work.resolve(n));
            }
        }
    }

    private FfmpegResult renderBlock(List<String> tracks, double xf, Path block, Path work, CancellationToken token)
            throws IOException {
        List<String> cmd = new ArrayList<>(List.of(tools.ffmpeg().toString()));
        for (String t : tracks) {
            cmd.addAll(List.of("-vn", "-i", t));
        }
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < tracks.size(); i++) {
            g.append('[').append(i).append(":a:0]").append(NORMALIZE).append("[a").append(i).append("];");
        }
        if (tracks.size() == 1) {
            g.append("[a0]anull[block]");
        } else if (xf > 0) {
            String prev = "a0";
            for (int i = 1; i < tracks.size(); i++) {
                String outLabel = i == tracks.size() - 1 ? "block" : "c" + i;
                g.append('[').append(prev).append("][a").append(i).append("]acrossfade=d=").append(num(xf))
                        .append(":c1=tri:c2=tri[").append(outLabel).append("];");
                prev = outLabel;
            }
            g.setLength(g.length() - 1);
        } else {
            for (int i = 0; i < tracks.size(); i++) {
                g.append("[a").append(i).append(']');
            }
            g.append("concat=n=").append(tracks.size()).append(":v=0:a=1[block]");
        }
        cmd.addAll(graph(work, "music_block.txt", g.toString()));
        cmd.addAll(List.of("-map", "[block]", "-c:a", "pcm_s16le", "-f", "wav", "-y", block.toString()));
        return FfmpegProcess.run(cmd, Duration.ZERO, null, token);
    }

    /** Loop unit: crossfade(tail of the block, head of the block) followed by the block's body. */
    private FfmpegResult renderUnit(Path block, double l, double xf, Path unit, Path work, CancellationToken token)
            throws IOException {
        String g = "[0:a]asplit=3[a][b][c];"
                + "[a]atrim=" + num(l - xf) + ":" + num(l) + ",asetpts=PTS-STARTPTS[tail];"
                + "[b]atrim=0:" + num(xf) + ",asetpts=PTS-STARTPTS[head];"
                + "[tail][head]acrossfade=d=" + num(xf) + ":c1=tri:c2=tri[x];"
                + "[c]atrim=" + num(xf) + ":" + num(l - xf) + ",asetpts=PTS-STARTPTS[body];"
                + "[x][body]concat=n=2:v=0:a=1[unit]";
        List<String> cmd = new ArrayList<>(List.of(tools.ffmpeg().toString(), "-i", block.toString()));
        cmd.addAll(graph(work, "music_unit.txt", g));
        cmd.addAll(List.of("-map", "[unit]", "-c:a", "pcm_s16le", "-f", "wav", "-y", unit.toString()));
        return FfmpegProcess.run(cmd, Duration.ZERO, null, token);
    }

    private FfmpegResult renderFinal(MusicSettings s, double v, Path block, double l, boolean loop, double xf,
                                     Path unit, Path original, Path work, Path out, CancellationToken token)
            throws IOException {
        List<String> cmd = new ArrayList<>(List.of(tools.ffmpeg().toString()));
        StringBuilder g = new StringBuilder();
        int next;
        if (loop && xf > 0) {
            cmd.addAll(List.of("-i", block.toString(), "-stream_loop", "-1", "-i", unit.toString()));
            g.append("[0:a]atrim=0:").append(num(l - xf)).append("[intro];[intro][1:a]concat=n=2:v=0:a=1[m];");
            next = 2;
        } else if (loop) {
            cmd.addAll(List.of("-stream_loop", "-1", "-i", block.toString()));
            g.append("[0:a]anull[m];");
            next = 1;
        } else {
            cmd.addAll(List.of("-i", block.toString()));
            g.append("[0:a]apad[m];"); // silence after the playlist
            next = 1;
        }
        g.append("[m]atrim=0:").append(num(v)).append(",asetpts=PTS-STARTPTS,volume=").append(num(s.volume()));
        if (s.fadeOut()) {
            double f = Math.min(s.fadeOutSeconds(), v);
            g.append(",afade=t=out:st=").append(num(v - f)).append(":d=").append(num(f));
        }
        boolean mix = s.mixOriginal() && original != null && Files.isRegularFile(original);
        g.append(mix ? "[mus];" : "[out]");
        if (mix) {
            cmd.addAll(List.of("-i", original.toString()));
            g.append('[').append(next).append(":a:0]").append(NORMALIZE).append(",volume=").append(num(s.originalVolume()))
                    .append(",apad,atrim=0:").append(num(v)).append("[orig];")
                    .append("[mus][orig]amix=inputs=2:duration=first:normalize=0[out]");
        }
        cmd.addAll(graph(work, "music_final.txt", g.toString()));
        cmd.addAll(List.of("-map", "[out]", "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2",
                "-t", num(v), "-f", "mp4", "-y", out.toString()));
        return FfmpegProcess.run(cmd, Duration.ofMillis(Math.round(v * 1000)), null, token);
    }

    /** Writes the filter graph to a file and returns the arguments that load it. */
    private static List<String> graph(Path work, String name, String graph) throws IOException {
        Path f = work.resolve(name);
        Files.writeString(f, graph, StandardCharsets.UTF_8);
        return List.of("-/filter_complex", f.toString());
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
