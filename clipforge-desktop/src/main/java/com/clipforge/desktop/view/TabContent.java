package com.clipforge.desktop.view;

import javafx.application.Platform;
import javafx.scene.layout.VBox;

/**
 * Base for the content of a settings tab. A TabPane does not pass the real width when it asks its content for the
 * preferred height, so wrapping texts would count as fewer lines and the scrollable side panel would end too early,
 * cutting off the last lines. This box answers for its current width instead, and asks for a new layout when the
 * width changes.
 */
abstract class TabContent extends VBox {

    protected TabContent() {
        widthProperty().addListener((o, a, b) -> {
            if (a.doubleValue() != b.doubleValue()) {
                // wrapped heights change with the width. The width is set while the parents are laying out, and
                // a layout request then does not reach them (their cached heights stay stale): ask again after it.
                Platform.runLater(this::requestLayout);
            }
        });
    }

    /*
     * The tab pane asks with no width or with our (single-line, wide) preferred width; either way the texts would
     * wrap less than they really do. Once laid out, the real width is what counts.
     */
    @Override
    protected double computePrefHeight(double width) {
        return super.computePrefHeight(getWidth() > 0 ? getWidth() : width);
    }

    @Override
    protected double computeMinHeight(double width) {
        return super.computeMinHeight(getWidth() > 0 ? getWidth() : width);
    }
}
