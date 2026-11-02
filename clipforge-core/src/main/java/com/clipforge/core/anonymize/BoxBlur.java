package com.clipforge.core.anonymize;

/** Separable box blur on one 8-bit plane region; three passes approximate a Gaussian. */
final class BoxBlur {

    private BoxBlur() {
    }

    static void blur3(byte[] plane, int offset, int stride, int x0, int y0, int w, int h, int radius) {
        int r = Math.max(1, Math.min(radius, Math.max(w, h)));
        int[] buf = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buf[y * w + x] = plane[offset + (y0 + y) * stride + x0 + x] & 0xff;
            }
        }
        int[] tmp = new int[w * h];
        for (int pass = 0; pass < 3; pass++) {
            horizontal(buf, tmp, w, h, r);
            vertical(tmp, buf, w, h, r);
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                plane[offset + (y0 + y) * stride + x0 + x] = (byte) buf[y * w + x];
            }
        }
    }

    /** Running-sum box filter with clamped edges. */
    private static void horizontal(int[] src, int[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sum = 0;
            for (int k = -r; k <= r; k++) {
                sum += src[row + Math.max(0, Math.min(w - 1, k))];
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = sum / div;
                sum += src[row + Math.min(w - 1, x + r + 1)] - src[row + Math.max(0, x - r)];
            }
        }
    }

    private static void vertical(int[] src, int[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int k = -r; k <= r; k++) {
                sum += src[Math.max(0, Math.min(h - 1, k)) * w + x];
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = sum / div;
                sum += src[Math.min(h - 1, y + r + 1) * w + x] - src[Math.max(0, y - r) * w + x];
            }
        }
    }
}
