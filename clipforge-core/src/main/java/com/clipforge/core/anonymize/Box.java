package com.clipforge.core.anonymize;

/**
 * A detected region in frame pixels.
 *
 * @param kind  what was found
 * @param score detector confidence 0..1
 */
public record Box(Kind kind, double x, double y, double w, double h, double score) {

    public enum Kind {
        PLATE,
        FACE
    }

    public double x2() {
        return x + w;
    }

    public double y2() {
        return y + h;
    }

    /** The box grown by {@code fraction} of its size on every side (hides the edges of a moving plate). */
    public Box grow(double fraction) {
        double dx = w * fraction;
        double dy = h * fraction;
        return new Box(kind, x - dx, y - dy, w + 2 * dx, h + 2 * dy, score);
    }

    public double iou(Box o) {
        double ix = Math.max(0, Math.min(x2(), o.x2()) - Math.max(x, o.x));
        double iy = Math.max(0, Math.min(y2(), o.y2()) - Math.max(y, o.y));
        double inter = ix * iy;
        double union = w * h + o.w * o.h - inter;
        return union <= 0 ? 0 : inter / union;
    }
}
