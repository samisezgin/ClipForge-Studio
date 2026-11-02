package com.clipforge.core.ffmpeg;

import com.clipforge.core.model.MediaInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeServiceTest {

    @Test
    void parsesDashCamProbe() throws Exception {
        String json = """
                {"streams":[
                  {"codec_type":"video","codec_name":"hevc","width":2304,"height":1296,"avg_frame_rate":"3603/120"},
                  {"codec_type":"audio","codec_name":"aac"},
                  {"codec_type":"data","codec_tag_string":"ssmd"},
                  {"codec_type":"data","codec_tag_string":"ssmd"}],
                 "format":{"duration":"120.261700","tags":{"creation_time":"2026-11-01T23:39:33.000000Z"}}}
                """;
        MediaInfo m = ProbeService.parse(json).orElseThrow();
        assertEquals(Duration.ofMillis(120262), m.duration());
        assertEquals("hevc", m.videoCodec());
        assertEquals("2304x1296", m.resolution());
        assertEquals(30.025, m.frameRate(), 0.001);
        assertEquals(2, m.dataStreams());
        assertEquals(Instant.parse("2026-11-01T23:39:33Z"), m.creationTime());
    }

    @Test
    void ignoresCoverArtAndEpochPlaceholders() throws Exception {
        String json = """
                {"streams":[
                  {"codec_type":"video","codec_name":"mjpeg","width":600,"height":600,"disposition":{"attached_pic":1}},
                  {"codec_type":"audio","codec_name":"mp3"}],
                 "format":{"duration":"3.5","tags":{"creation_time":"1970-01-01T00:00:00.000000Z"}}}
                """;
        MediaInfo m = ProbeService.parse(json).orElseThrow();
        assertNull(m.videoCodec());
        assertNull(m.creationTime());
    }

    @Test
    void noDurationMeansUnreadable() throws Exception {
        Optional<MediaInfo> m = ProbeService.parse("{\"streams\":[],\"format\":{}}");
        assertTrue(m.isEmpty());
    }
}
