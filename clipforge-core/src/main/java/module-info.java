/**
 * UI-independent core of ClipForge Studio. Desktop and (future) web front-ends depend only on this module.
 */
module com.clipforge.core {
    requires transitive java.logging;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;

    exports com.clipforge.core;
}
