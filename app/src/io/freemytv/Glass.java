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

import java.util.HashMap;
import java.util.Map;

/**
 * Liquid glass, as clear as the iPhone's lock screen clock: what is behind shows through almost untouched (a touch
 * brighter and more saturated); a thin light rim, a soft light band just inside the edge (the glass's thickness), a
 * hairline highlight on the top edge and a faint shadow give the shape.
 *  - clock(): the clock's digits, made once a minute in software (a bitmap drawn as it is);
 *  - panel(): a rounded panel (the side bar, the shelf), drawn on every frame from the background image's shader.
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

    /** Paints for the panels, made once per screen (nothing is allocated while drawing). */
    static final class Panel {
        final Paint through = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG),
                fill = new Paint(Paint.ANTI_ALIAS_FLAG), shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Matrix matrix = new Matrix();
        final RectF r = new RectF();
        final Map<String, Bitmap> shadows = new HashMap<>();
        float k = 1;

        Panel() {
            through.setColorFilter(lift(1.07f, 1.2f));
            rim.setStyle(Paint.Style.STROKE);
            glow.setStyle(Paint.Style.STROKE);
            fill.setColor(0x05FFFFFF);
            shadowPaint.setColor(0xFF000000);
        }

        /**
         * A glass panel at `rect`: the background (`bg`, drawn to fill `screenW` × `screenH`) seen through it, then the
         * rim and the light inside the edge; `alpha` fades all of it. `bg2` and `mix`: a second background fading in.
         */
        void draw(Canvas c, RectF rect, float radius, Bitmap bg, Bitmap bg2, float mix, int screenW, int screenH, float alpha) {
            if (alpha <= 0.002f) return;
            drawShadow(c, rect, radius, alpha);
            int a = Math.round(255 * alpha);
            if (bg != null) drawThrough(c, rect, radius, bg, screenW, screenH, a);
            if (bg2 != null && mix > 0) drawThrough(c, rect, radius, bg2, screenW, screenH, Math.round(a * mix));
            fill.setAlpha(Math.round(5 * alpha));
            c.drawRoundRect(rect, radius, radius, fill);
            // Light just inside the edge, fading inward: the glass's thickness.
            float[] insets = {2, 5, 9, 14};
            int[] alphas = {0x2E, 0x1A, 0x0E, 0x06};
            for (int i = 0; i < insets.length; i++) {
                float in = insets[i] * k;
                r.set(rect.left + in, rect.top + in, rect.right - in, rect.bottom - in);
                glow.setStrokeWidth(Math.max(1, 3.5f * k));
                glow.setColor(Ui.alpha(0xFFFFFFFF, alphas[i] / 255f * alpha));
                c.drawRoundRect(r, Math.max(0, radius - in), Math.max(0, radius - in), glow);
            }
            // The rim, and a hairline highlight along the top.
            rim.setStrokeWidth(Math.max(1, 1.5f * k));
            rim.setColor(Ui.alpha(0x57FFFFFF, alpha));
            c.drawRoundRect(rect, radius, radius, rim);
            int save = c.save();
            c.clipRect(rect.left, rect.top - 2, rect.right, rect.top + radius * 0.8f);
            rim.setStrokeWidth(Math.max(1, 2.2f * k));
            rim.setColor(Ui.alpha(0x99FFFFFF, alpha));
            r.set(rect.left + k, rect.top + k, rect.right - k, rect.bottom - k);
            c.drawRoundRect(r, radius, radius, rim);
            c.restoreToCount(save);
        }

        private void drawThrough(Canvas c, RectF rect, float radius, Bitmap bg, int screenW, int screenH, int a) {
            BitmapShader s = shader(bg);
            matrix.setScale(screenW / (float) bg.getWidth(), screenH / (float) bg.getHeight());
            s.setLocalMatrix(matrix);
            through.setShader(s);
            through.setAlpha(a);
            c.drawRoundRect(rect, radius, radius, through);
            through.setShader(null);
        }

        /** Two backgrounds alternate during a crossfade: one shader per bitmap, made once. */
        private final Map<Bitmap, BitmapShader> shaders = new HashMap<>();

        private BitmapShader shader(Bitmap b) {
            BitmapShader s = shaders.get(b);
            if (s == null) {
                if (shaders.size() > 3) shaders.clear();
                s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shaders.put(b, s);
            }
            return s;
        }

        /** The soft shadow under the panel: a blurred rounded rectangle, made once per size (blur is software only). */
        private void drawShadow(Canvas c, RectF rect, float radius, float alpha) {
            int w = Math.round(rect.width()), h = Math.round(rect.height()), blur = Math.max(2, Math.round(18 * k));
            String key = w + "x" + h;
            Bitmap b = shadows.get(key);
            if (b == null) {
                if (shadows.size() > 6) shadows.clear();
                b = Bitmap.createBitmap(w + 4 * blur, h + 4 * blur, Bitmap.Config.ALPHA_8);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
                new Canvas(b).drawRoundRect(2 * blur, 2 * blur, 2 * blur + w, 2 * blur + h, radius, radius, p);
                shadows.put(key, b);
            }
            shadowPaint.setAlpha(Math.round(46 * alpha));
            c.drawBitmap(b, rect.left - 2 * blur, rect.top - 2 * blur + 8 * k, shadowPaint);
        }
    }
}
