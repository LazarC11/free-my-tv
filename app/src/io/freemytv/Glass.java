package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 *  - clock(): the clock's digits in clear glass, as the iPhone's lock screen clock (made once a minute in software);
 *  - Panel: a plain see-through panel (the side bar, the shelf, the weather): a translucent fill, a hairline edge.
 */
final class Glass {
    private Glass() {}

    // MARK: - The clock

    /**
     * The digits of `text` as glass over `behind` (the background exactly under `w` × `h`, the bitmap's own size),
     * right-aligned at `right`, baseline `base`, in `paint`'s typeface and size. Background thread.
     */
    static Bitmap clock(Bitmap behind, String text, Paint font, float right, float base, float k) {
        int w = behind.getWidth(), h = behind.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(font);
        p.setTextAlign(Paint.Align.LEFT);
        float x = right - p.measureText(text);
        Path glyphs = new Path();
        p.getTextPath(text, 0, text.length(), x, base, glyphs);
        glyphs.setFillType(Path.FillType.WINDING);

        // A faint shadow under the glass.
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x1A000000);
        shadow.setMaskFilter(new BlurMaskFilter(Math.max(1, 6 * k), BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, 4 * k);
        c.drawPath(glyphs, shadow);
        c.restore();

        // The background through the glass: magnified 3% like a lens, a touch brighter.
        BitmapShader through = new BitmapShader(behind, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1.03f, 1.03f, w * 0.62f, base - 55 * k);
        through.setLocalMatrix(m);
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        body.setShader(through);
        body.setColorFilter(lift(1.05f, 1.15f));
        c.drawPath(glyphs, body);
        Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tint.setColor(0x08FFFFFF);
        c.drawPath(glyphs, tint);

        // The glass's thickness: a soft light band just inside the edge.
        Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
        band.setColor(0x29FFFFFF);
        band.setMaskFilter(new BlurMaskFilter(Math.max(1, 9 * k), BlurMaskFilter.Blur.INNER));
        c.drawPath(glyphs, band);

        // A hairline highlight on the top edges, a faint dark one on the bottom edges.
        edge(c, glyphs, 1.2f * k, 2.2f * k, 0xCCFFFFFF, 0.8f * k);
        edge(c, glyphs, -1f * k, -2.5f * k, 0x38000000, 1.2f * k);

        // The rim, just outside.
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1, 2 * k));
        Path outline = new Path();
        stroke.getFillPath(glyphs, outline);
        outline.op(glyphs, Path.Op.DIFFERENCE);
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setColor(0x61FFFFFF);
        c.drawPath(outline, rim);
        return out;
    }

    /** The part of the glyphs the shifted glyphs don't cover: a crescent along one side of every edge. */
    private static void edge(Canvas c, Path glyphs, float dx, float dy, int color, float blur) {
        Path shifted = new Path(glyphs);
        shifted.offset(dx, dy);
        Path crescent = new Path(glyphs);
        crescent.op(shifted, Path.Op.DIFFERENCE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        if (blur >= 0.5f) p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(crescent, p);
    }

    /** Brighter and more saturated (what is behind glass looks a little clearer). */
    static ColorMatrixColorFilter lift(float brightness, float saturation) {
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(saturation);
        ColorMatrix bright = new ColorMatrix();
        bright.setScale(brightness, brightness, brightness, 1);
        sat.postConcat(bright);
        return new ColorMatrixColorFilter(sat);
    }

    // MARK: - Panels

    /**
     * A plain see-through panel (the side bar, the shelf, the weather, the dots): a translucent dark fill and a faint
     * hairline edge, nothing else (no blur, shine or shadow). Paints made once (nothing is allocated while drawing).
     */
    static final class Panel {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        float k = 1;
        /** How dark the fill is, 0 to 1: 1 - Prefs.transparency() / 100, set by the screen that draws. */
        float opacity = 0.3f;

        Panel() {
            edge.setStyle(Paint.Style.STROKE);
        }

        /** The panel at `rect`, faded by `alpha`. The background arguments are unused (kept for the callers). */
        void draw(Canvas c, RectF rect, float radius, Bitmap bg, Bitmap bg2, float mix, int screenW, int screenH, float alpha) {
            if (alpha <= 0.002f) return;
            fill.setColor(Ui.alpha(FILL, opacity * alpha));
            c.drawRoundRect(rect, radius, radius, fill);
            edge.setStrokeWidth(Math.max(1, 1.2f * k));
            edge.setColor(Ui.alpha(EDGE, alpha));
            c.drawRoundRect(rect, radius, radius, edge);
        }

        private static final int FILL = 0xFF0E0F13, EDGE = 0x24FFFFFF;
    }
}