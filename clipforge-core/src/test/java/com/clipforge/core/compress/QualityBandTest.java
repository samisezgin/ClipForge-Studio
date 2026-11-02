package com.clipforge.core.compress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityBandTest {

    @Test
    void bandsCoverTheWholeSliderRangeWithoutGaps() {
        for (EncoderProfile p : EncoderProfile.values()) {
            int expectedFrom = p.minQuality();
            for (QualityBand b : QualityBand.values()) {
                int[] r = p.range(b);
                assertEquals(expectedFrom, r[0], p + " " + b);
                assertTrue(r[1] >= r[0], p + " " + b);
                for (int q = r[0]; q <= r[1]; q++) {
                    assertEquals(b, p.band(q), p + " q=" + q);
                }
                expectedFrom = r[1] + 1;
            }
            assertEquals(p.maxQuality() + 1, expectedFrom, p.toString());
        }
    }

    @Test
    void defaultsAreInTheGoodBand() {
        for (EncoderProfile p : EncoderProfile.values()) {
            assertEquals(QualityBand.GOOD, p.band(p.defaultQuality()), p.toString());
        }
    }
}
