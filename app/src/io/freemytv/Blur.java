package io.freemytv;

import android.graphics.Bitmap;

/**
 * A smooth blur for the backgrounds behind the glass and the scrolled page: the picture scaled down, then three box
 * blurs each way (close to a Gaussian, without the blocks that scaling alone leaves). Background thread: it is
 * done once per picture, never while drawing.
 */
final class Blur {
    private Blur() {}

    /** `src` at `w` × `h`, blurred by `radius` pixels of that size (a new, mutable bitmap). */
    static Bitmap soft(Bitmap src, int w, int h, int radius) {
        Bitmap small = Bitmap.createScaledBitmap(src, Math.max(1, w), Math.max(1, h), true);
        Bitmap b = small.isMutable() && small.getConfig() == Bitmap.Config.ARGB_8888 && small != src
                ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (b != small && small != src) small.recycle();
        int bw = b.getWidth(), bh = b.getHeight();
        int[] px = new int[bw * bh], tmp = new int[bw * bh];
        b.getPixels(px, 0, bw, 0, 0, bw, bh);
        for (int pass = 0; pass < 3; pass++) {
            horizontal(px, tmp, bw, bh, radius);
            vertical(tmp, px, bw, bh, radius);
        }
        b.setPixels(px, 0, bw, 0, 0, bw, bh);
        return b;
    }

    /** A running box sum along each row (edges repeated). */
    private static void horizontal(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[row + clamp(i, w)];
                sa += p >>> 24; sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[row + clamp(x + r + 1, w)], sub = in[row + clamp(x - r, w)];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** The same down each column. */
    private static void vertical(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[clamp(i, h) * w + x];
                sa += p >>> 24; sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[clamp(y + r + 1, h) * w + x], sub = in[clamp(y - r, h) * w + x];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : i >= n ? n - 1 : i;
    }
}
