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
 *     <li>The playlist is decoded into one block (48 kHz stereo, mono tracks up-mixed without a level drop), with an
 *     equal-power crossfade between consecutive tracks. A playlist longer than the video is cut early.</li>
 *     <li>The block's real length is measured (probed durations of VBR MP3s can be off by seconds).</li>
 *     <li>For looping, a seamless loop unit is built once: the block's tail crossfaded into its head, followed by the
 *     block's body. Playing {@code head-part + unit + unit + ...} equals the block repeated with a crossfade at every
 *     restart, for any video length, with constant memory ({@code -stream_loop -1}).</li>
 *     <li>The result is trimmed to the video length, its volume set, faded out (at the video end, or at the end of the
 *     music when it plays once), optionally mixed with the original audio, and limited so loud settings never clip.</li>
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

    /** A track's probed length and channel count. */
    record TrackInfo(String path, double seconds, int channels) {
    }

    private static final String RESAMPLE = "aresample=48000";
    private static final String STEREO = "aformat=sample_fmts=fltp:channel_layouts=stereo";
    /** Ceiling (-1 dBFS, headroom for AAC) so volume > 100 % or a loud original mix never clips. */
    private static final String LIMITER = "alimiter=limit=0.89:latency=1:level=disabled";

    private final FfmpegTools tools;

    public MusicRenderer(FfmpegTools tools) {
        this.tools = tools;
    }

    /** Durations of the tracks in seconds; throws for the first unreadable file. */
    public List<Double> durations(List<String> tracks) throws TrackException {
        return probe(tracks).stream().map(TrackInfo::seconds).toList();
    }

    private List<TrackInfo> probe(List<String> tracks) throws TrackException {
        ProbeService probe = new ProbeService(tools);
        List<TrackInfo> list = new ArrayList<>();
        for (String t : tracks) {
            Optional<MediaInfo> m = Files.isRegularFile(Path.of(t)) ? probe.probe(Path.of(t)) : Optional.empty();
            if (m.isEmpty() || !m.get().hasAudio()) {
                throw new TrackException(Path.of(t).getFileName().toString());
            }
            list.add(new TrackInfo(t, m.get().duration().toMillis() / 1000.0, m.get().audioChannels()));
        }
        return list;
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
        List<TrackInfo> tracks = probe(s.tracks());
        double estimated = tracks.stream().mapToDouble(TrackInfo::seconds).sum();

        Path block = work.resolve("music_block.wav");
        Path unit = work.resolve("music_unit.wav");
        try {
            // a playlist longer than the video does not need to be decoded completely
            double limit = estimated > videoSeconds + 5 ? videoSeconds + 2 * MusicSettings.MAX_CROSSFADE + 1 : 0;
            FfmpegResult r = renderBlock(tracks, s.crossfadeSeconds(), limit, block, work, token);
            if (!r.success()) {
                return r;
            }
            double l = measured(block);
            boolean loop = s.loop() && l < videoSeconds;
            // the restart crossfade must fit twice into the block; shrink it instead of dropping it
            double loopXf = loop ? Math.min(s.crossfadeSeconds(), (l - 0.1) / 2) : 0;
            if (loopXf < 0.05) {
                loopXf = 0;
            }
            if (loop && loopXf > 0) {
                r = renderUnit(block, l, loopXf, unit, work, token);
                if (!r.success()) {
                    return r;
                }
            }
            return renderFinal(s, videoSeconds, block, l, loop, loopXf, unit, original, work, out, token);
        } finally {
            Files.deleteIfExists(block);
            Files.deleteIfExists(unit);
            for (String n : List.of("music_block.txt", "music_unit.txt", "music_final.txt")) {
                Files.deleteIfExists(work.resolve(n));
            }
        }
    }

    /** Real length of the rendered block (probing the WAV is exact, unlike VBR MP3 headers). */
    private double measured(Path block) throws IOException {
        Optional<MediaInfo> m = new ProbeService(tools).probe(block);
        if (m.isEmpty()) {
            throw new IOException("Rendered music block cannot be read");
        }
        return m.get().duration().toMillis() / 1000.0;
    }

    private FfmpegResult renderBlock(List<TrackInfo> tracks, double crossfade, double limit, Path block, Path work,
                                     CancellationToken token) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(tools.ffmpeg().toString()));
        for (TrackInfo t : tracks) {
            cmd.addAll(List.of("-vn", "-i", t.path()));
        }
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < tracks.size(); i++) {
            // mono is copied to both channels at full level (the default up-mix would be 3 dB quieter)
            String upmix = tracks.get(i).channels() == 1 ? "pan=stereo|c0=c0|c1=c0," : "";
            g.append('[').append(i).append(":a:0]").append(RESAMPLE).append(',').append(upmix).append(STEREO)
                    .append("[a").append(i).append("];");
        }
        String prev = "a0";
        for (int i = 1; i < tracks.size(); i++) {
            // per pair: a short track only shortens its own crossfades
            double xf = Math.max(0, Math.min(crossfade,
                    Math.min(tracks.get(i - 1).seconds(), tracks.get(i).seconds()) / 2 - 0.05));
            String label = "c" + i;
            g.append('[').append(prev).append("][a").append(i).append(']');
            if (xf >= 0.05) {
                // equal-power curves: no loudness dip in the middle of a crossfade between different songs
                g.append("acrossfade=d=").append(num(xf)).append(":c1=qsin:c2=qsin");
            } else {
                g.append("concat=n=2:v=0:a=1");
            }
            g.append('[').append(label).append("];");
            prev = label;
        }
        g.append('[').append(prev).append("]anull[block]");
        cmd.addAll(graph(work, "music_block.txt", g.toString()));
        cmd.addAll(List.of("-map", "[block]", "-c:a", "pcm_s16le", "-rf64", "auto"));
        if (limit > 0) {
            cmd.addAll(List.of("-t", num(limit)));
        }
        cmd.addAll(List.of("-f", "wav", "-y", block.toString()));
        return FfmpegProcess.run(cmd, Duration.ZERO, null, token);
    }

    /** Loop unit: crossfade(tail of the block, head of the block) followed by the block's body. */
    private FfmpegResult renderUnit(Path block, double l, double xf, Path unit, Path work, CancellationToken token)
            throws IOException {
        String g = "[0:a]asplit=3[a][b][c];"
                + "[a]atrim=" + num(l - xf) + ":" + num(l) + ",asetpts=PTS-STARTPTS[tail];"
                + "[b]atrim=0:" + num(xf) + ",asetpts=PTS-STARTPTS[head];"
                // same material on both sides of the seam: a linear crossfade keeps the level constant
                + "[tail][head]acrossfade=d=" + num(xf) + ":c1=tri:c2=tri[x];"
                + "[c]atrim=" + num(xf) + ":" + num(l - xf) + ",asetpts=PTS-STARTPTS[body];"
                + "[x][body]concat=n=2:v=0:a=1[unit]";
        List<String> cmd = new ArrayList<>(List.of(tools.ffmpeg().toString(), "-i", block.toString()));
        cmd.addAll(graph(work, "music_unit.txt", g));
        cmd.addAll(List.of("-map", "[unit]", "-c:a", "pcm_s16le", "-rf64", "auto", "-f", "wav", "-y", unit.toString()));
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
            // when the music plays once and ends before the video, fade at its own end (not in the silence)
            double end = !loop && l < v ? l : v;
            double f = Math.min(s.fadeOutSeconds(), end);
            g.append(",afade=t=out:st=").append(num(end - f)).append(":d=").append(num(f));
        }
        boolean mix = s.mixOriginal() && original != null && Files.isRegularFile(original);
        if (mix) {
            cmd.addAll(List.of("-i", original.toString()));
            g.append("[mus];[").append(next).append(":a:0]").append(RESAMPLE).append(',').append(STEREO)
                    .append(",volume=").append(num(s.originalVolume())).append(",apad,atrim=0:").append(num(v));
            if (s.fadeOut()) {
                double f = Math.min(s.fadeOutSeconds(), v);
                g.append(",afade=t=out:st=").append(num(v - f)).append(":d=").append(num(f));
            }
            g.append("[orig];[mus][orig]amix=inputs=2:duration=first:normalize=0");
        }
        g.append(',').append(LIMITER).append("[out]");
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
