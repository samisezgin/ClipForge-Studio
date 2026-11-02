package com.clipforge.desktop.view;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;

/**
 * Keeps text readable in narrow panels: wrapping labels grow to as many lines as they need, and any single-line
 * text cut off with "…" shows its full text as a tooltip on hover.
 */
public final class TextFit {

    private static final String AUTO_TIP = "clipforge.autoTooltip";

    private TextFit() {
    }

    /**
     * A wrapping label inside a VBox may still be cut to one line, because its minimum height defaults to one line;
     * using the preferred height as minimum lets it take all the lines it needs.
     */
    static void fixWrapping(Node node) {
        if (node instanceof Label l && l.isWrapText()) {
            l.setMinHeight(Region.USE_PREF_SIZE);
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                fixWrapping(child);
            }
        }
    }

    /** Shows the full text of truncated labels, buttons, cells and tabs on hover, anywhere in the scene. */
    public static void installTruncationTooltips(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            Node n = e.getPickResult().getIntersectedNode();
            while (n != null && !(n instanceof Labeled)) {
                n = n.getParent();
            }
            if (n instanceof Labeled labeled) {
                update(labeled);
            }
        });
    }

    private static void update(Labeled labeled) {
        boolean truncated = isTruncated(labeled);
        boolean auto = Boolean.TRUE.equals(labeled.getProperties().get(AUTO_TIP));
        if (truncated && (labeled.getTooltip() == null || auto)) {
            Tooltip tip = labeled.getTooltip();
            if (tip == null) {
                tip = new Tooltip();
                tip.setWrapText(true);
                tip.setMaxWidth(480);
                labeled.setTooltip(tip);
                labeled.getProperties().put(AUTO_TIP, true);
            }
            tip.setText(labeled.getText());
        } else if (!truncated && auto) {
            // wider again: the full text is visible, the automatic tooltip is not needed any more
            labeled.setTooltip(null);
            labeled.getProperties().remove(AUTO_TIP);
        }
    }

    /** The skin shows the cut text (ending in the ellipsis) in a Text node; compare it with the full text. */
    private static boolean isTruncated(Labeled labeled) {
        String full = labeled.getText();
        if (full == null || full.isEmpty()) {
            return false;
        }
        for (Node child : labeled.getChildrenUnmodifiable()) {
            if (child instanceof Text t && t.getText() != null && !t.getText().equals(full)) {
                return true;
            }
        }
        return false;
    }
}
