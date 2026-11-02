package com.clipforge.core.anonymize;

import java.util.Arrays;

/**
 * One decoded video frame in planar YUV 4:2:0 (FFmpeg {@code -pix_fmt yuv420p}): a full-size Y plane followed by
 * quarter-size U and V planes. Width and height are even.
 */
public final class YuvFrame {

    public final int width;
    public final int height;
    public final byte[] data;

    public YuvFrame(int width, int height) {
        this(width, height, new byte[size(width, height)]);
    }

    public YuvFrame(int width, int height, byte[] data) {
        if (width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("odd frame size " + width + "x" + height);
        }
        if (data.length != size(width, height)) {
            throw new IllegalArgumentException("frame buffer has " + data.length + " bytes");
        }
        this.width = width;
        this.height = height;
        this.data = data;
    }

    public static int size(int width, int height) {
        return width * height * 3 / 2;
    }

    int uOffset() {
        return width * height;
    }

    int vOffset() {
        return width * height + (width / 2) * (height / 2);
    }

    /**
     * Fills {@code chw} (3 x size x size floats, RGB or BGR order) with the frame scaled to fit a square of
     * {@code size} pixels, keeping the aspect ratio and padding with {@code pad} (letterbox).
     *
     * @param scale multiplies the 0..255 values (1/255 for models expecting 0..1)
     * @return the scale factor from frame pixels to model pixels; the image is placed at the top left
     */
    public double letterbox(float[] chw, int size, boolean bgr, float scale, float pad) {
        return letterbox(chw, size, bgr, scale, pad, 0, 0, width, height);
    }

    /**
     * Like {@link #letterbox(float[], int, boolean, float, float)} for the region {@code (rx, ry, rw, rh)} of the
     * frame (a tile): small plates get more model pixels than in the whole frame.
     */
    public double letterbox(float[] chw, int size, boolean bgr, float scale, float pad, int rx, int ry, int rw, int rh) {
        double f = Math.min((double) size / rw, (double) size / rh);
        int nw = (int) Math.round(rw * f);
        int nh = (int) Math.round(rh * f);
        int plane = size * size;
        Arrays.fill(chw, 0, 3 * plane, pad * scale);
        int cw = width / 2;
        int uo = uOffset();
        int vo = vOffset();
        int r = bgr ? 2 : 0;
        int b = bgr ? 0 : 2;
        for (int y = 0; y < nh; y++) {
            double sy = ry + (y + 0.5) / f - 0.5;
            int y0 = clamp((int) Math.floor(sy), 0, height - 1);
            int y1 = Math.min(y0 + 1, height - 1);
            double fy = sy - Math.floor(sy);
            int cy = clamp((int) (sy / 2), 0, height / 2 - 1);
            for (int x = 0; x < nw; x++) {
                double sx = rx + (x + 0.5) / f - 0.5;
                int x0 = clamp((int) Math.floor(sx), 0, width - 1);
                int x1 = Math.min(x0 + 1, width - 1);
                double fx = sx - Math.floor(sx);
                // bilinear luma keeps small plates readable for the detector; chroma is smooth anyway
                double lum = (1 - fy) * ((1 - fx) * (data[y0 * width + x0] & 0xff) + fx * (data[y0 * width + x1] & 0xff))
                        + fy * ((1 - fx) * (data[y1 * width + x0] & 0xff) + fx * (data[y1 * width + x1] & 0xff));
                int cx = clamp((int) (sx / 2), 0, cw - 1);
                int u = (data[uo + cy * cw + cx] & 0xff) - 128;
                int v = (data[vo + cy * cw + cx] & 0xff) - 128;
                // BT.709 limited range to full-range RGB
                double c = (lum - 16) * 1.164;
                float red = (float) clamp(c + 1.793 * v);
                float green = (float) clamp(c - 0.213 * u - 0.533 * v);
                float blue = (float) clamp(c + 2.112 * u);
                int i = y * size + x;
                chw[r * plane + i] = red * scale;
                chw[plane + i] = green * scale;
                chw[b * plane + i] = blue * scale;
            }
        }
        return f;
    }

    /**
     * Strong Gaussian-like blur (three box blurs) of the region on all three planes. The radius grows with the
     * region, so a plate is unreadable at any distance; a weak blur could be partly undone.
     */
    public void blur(Box region) {
        int x0 = clamp((int) Math.floor(region.x()), 0, width - 1) & ~1;
        int y0 = clamp((int) Math.floor(region.y()), 0, height - 1) & ~1;
        int x1 = clamp((int) Math.ceil(region.x2()), 0, width);
        int y1 = clamp((int) Math.ceil(region.y2()), 0, height);
        int w = (x1 - x0) & ~1;
        int h = (y1 - y0) & ~1;
        if (w < 2 || h < 2) {
            return;
        }
        int radius = Math.max(6, (int) Math.round(Math.min(w, h) * 0.35));
        BoxBlur.blur3(data, 0, width, x0, y0, w, h, radius);
        int cw = width / 2;
        int cr = Math.max(3, radius / 2);
        BoxBlur.blur3(data, uOffset(), cw, x0 / 2, y0 / 2, w / 2, h / 2, cr);
        BoxBlur.blur3(data, vOffset(), cw, x0 / 2, y0 / 2, w / 2, h / 2, cr);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(255, v));
    }
}
