package com.clipforge.core.telemetry;

import java.time.Duration;
import java.time.Instant;

/**
 * One telemetry reading. Fields a source does not provide are {@code null}.
 *
 * @param offset   position in the clip
 * @param time     absolute time of the reading (camera clock or GPS time), if known
 * @param latitude degrees, WGS84
 * @param longitude degrees, WGS84
 * @param altitude metres
 * @param speedKmh ground speed in km/h
 * @param heading  degrees from north
 */
public record TelemetrySample(Duration offset, Instant time, Double latitude, Double longitude, Double altitude,
                              Double speedKmh, Double heading) {

    public boolean hasPosition() {
        return latitude != null && longitude != null
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180
                && !(latitude == 0 && longitude == 0);
    }

    public TelemetrySample shifted(Duration by) {
        return new TelemetrySample(offset.plus(by), time, latitude, longitude, altitude, speedKmh, heading);
    }
}
