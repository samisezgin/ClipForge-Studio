/**
 * UI-independent core of ClipForge Studio. Desktop and (future) web front-ends depend only on this module.
 */
module com.clipforge.core {
    requires transitive java.logging;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.datatype.jsr310;

    exports com.clipforge.core;
    exports com.clipforge.core.audio;
    exports com.clipforge.core.compress;
    exports com.clipforge.core.ffmpeg;
    exports com.clipforge.core.i18n;
    exports com.clipforge.core.job;
    exports com.clipforge.core.merge;
    exports com.clipforge.core.model;
    exports com.clipforge.core.mp4;
    exports com.clipforge.core.project;
    exports com.clipforge.core.scan;
    exports com.clipforge.core.telemetry;
    exports com.clipforge.core.util;

    opens com.clipforge.core.model to com.fasterxml.jackson.databind;
    opens com.clipforge.core.project to com.fasterxml.jackson.databind;
    opens com.clipforge.core.merge to com.fasterxml.jackson.databind;
    opens com.clipforge.core.ffmpeg to com.fasterxml.jackson.databind;
    opens com.clipforge.core.compress to com.fasterxml.jackson.databind;
    opens com.clipforge.core.audio to com.fasterxml.jackson.databind;
}
