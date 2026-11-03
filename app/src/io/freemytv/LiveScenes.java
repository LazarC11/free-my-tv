package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * The screensaver's live scenes, drawn by hand on a Canvas: an aquarium, a fireplace and northern lights. Each one
 * paints its still parts once into a background image (`prepare`, on a background thread) and on every frame only
 * what moves. Sizes are in a 1920×1080 space (k = width / 1920). Nothing is allocated while drawing.
 */
final class LiveScenes {
    private LiveScenes() {}

    static final int COUNT = 3;

    static Live make(int index) {
        switch (Math.floorMod(index, COUNT)) {
            case 0: return new Aquarium();
            case 1: return new Fireplace();
            default: return new Aurora();
        }
    }

    abstract static class Live {
        int w, h;
        float k;
        Bitmap background;
        final Random random = new Random();
        final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

        /** Builds the still parts at this size. Background thread. */
        final void prepare(int width, int height) {
            w = width;
            h = height;
            k = width / 1920f;
            background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            paintBackground(new Canvas(background));
            setup();
        }

        abstract void paintBackground(Canvas c);

        abstract void setup();

        /** One frame: t is the time in seconds, dt the time since the last frame. */
        abstract void draw(Canvas c, float t, float dt);

        void release() {
            if (background != null) background.recycle();
            background = null;
        }

        float rnd(float a, float b) {
            return a + random.nextFloat() * (b - a);
        }
    }

    /** Light added on top of what is there (flames, glows, the aurora). */
    static void additive(Paint p) {
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    /** A soft round glow: `inner` at the middle, fading to nothing. */
    static Bitmap glow(int size, int inner, int middle) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(size / 2f, size / 2f, size / 2f, new int[] {inner, middle, middle & 0x00FFFFFF},
                new float[] {0, 0.4f, 1}, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, size, size, p);
        return b;
    }

    // MARK: - Aquarium

    /** Fish of all colors crossing a blue tank, seaweed swaying, bubbles rising and light rays from the surface. */
    static final class Aquarium extends Live {
        private static final int FISH = 12, WEEDS = 14, BUBBLES = 46, RAYS = 6;
        private static final int[][] COLORS = {
            {0xFFFF8A2A, 0xFFFFC84A}, {0xFF35C6F4, 0xFF1C6FD6}, {0xFFF2648E, 0xFFB43B92}, {0xFFFFE15A, 0xFFE89A18},
            {0xFF7CF0B4, 0xFF1EA27A}, {0xFFB89CFF, 0xFF5B47D6}, {0xFFFF6A4A, 0xFFC8203A},
        };

        private final float[] fx = new float[FISH], fy = new float[FISH], fSpeed = new float[FISH], fSize = new float[FISH],
                fPhase = new float[FISH], fDepth = new float[FISH], fBob = new float[FISH];
        private final int[] fDir = new int[FISH], fColor = new int[FISH];
        private final Paint[] bodies = new Paint[COLORS.length], tails = new Paint[COLORS.length];
        private final float[] wx = new float[WEEDS], wh = new float[WEEDS], wPhase = new float[WEEDS], wWidth = new float[WEEDS];
        private final int[] wColor = new int[WEEDS];
        private final float[] bx = new float[BUBBLES], by = new float[BUBBLES], br = new float[BUBBLES], bSpeed = new float[BUBBLES],
                bPhase = new float[BUBBLES];
        private final float[] rx = new float[RAYS], rw = new float[RAYS], rPhase = new float[RAYS];
        private final Path body = new Path(), tail = new Path(), ray = new Path();
        private final Paint eye = new Paint(Paint.ANTI_ALIAS_FLAG), pupil = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint weed = new Paint(Paint.ANTI_ALIAS_FLAG), bubble = new Paint(Paint.ANTI_ALIAS_FLAG),
                shine = new Paint(Paint.ANTI_ALIAS_FLAG), rayPaint = new Paint(Paint.ANTI_ALIAS_FLAG), fin = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override void paintBackground(Canvas c) {
            Paint p = new Paint(Paint.DITHER_FLAG | Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, h, new int[] {0xFF0F5A82, 0xFF0A3B5E, 0xFF06223A, 0xFF041322},
                    new float[] {0, 0.3f, 0.65f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w * 0.45f, -h * 0.1f, w * 0.7f, 0x6640B4E0, 0x0040B4E0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            // Sand: a low wavy dune across the bottom, lit from above.
            Path sand = new Path();
            float base = h * 0.86f;
            sand.moveTo(0, h);
            sand.lineTo(0, base);
            for (int x = 0; x <= w; x += Math.max(4, w / 96)) {
                float y = base + (float) (Math.sin(x * 0.004) * 16 * k + Math.sin(x * 0.011 + 1.3) * 8 * k);
                sand.lineTo(x, y);
            }
            sand.lineTo(w, h);
            sand.close();
            p.setShader(new LinearGradient(0, base - 20 * k, 0, h, 0xFF6E6448, 0xFF2A2619, Shader.TileMode.CLAMP));
            c.drawPath(sand, p);
            // Pebbles and rocks.
            p.setShader(null);
            for (int i = 0; i < 70; i++) {
                float x = rnd(0, w), y = rnd(base + 20 * k, h), r = rnd(2, 7) * k;
                p.setColor(Color.argb(Math.round(rnd(60, 140)), 40 + random.nextInt(40), 36 + random.nextInt(30), 28 + random.nextInt(20)));
                c.drawCircle(x, y, r, p);
            }
            for (int i = 0; i < 5; i++) {
                float x = rnd(0.05f, 0.95f) * w, r = rnd(50, 110) * k;
                p.setShader(new RadialGradient(x - r * 0.3f, base - r * 0.2f, r * 1.4f, 0xFF3C4A52, 0xFF141C22, Shader.TileMode.CLAMP));
                c.drawOval(new RectF(x - r * 1.3f, base - r * 0.7f, x + r * 1.3f, base + r * 0.6f), p);
            }
            // Distance haze at the far bottom.
            p.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0x00041322, 0x33041322, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }

        @Override void setup() {
            for (int i = 0; i < COLORS.length; i++) {
                bodies[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                bodies[i].setShader(new LinearGradient(0, -1, 0, 1, COLORS[i][0], COLORS[i][1], Shader.TileMode.CLAMP));
                tails[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                tails[i].setColor(COLORS[i][1]);
            }
            for (int i = 0; i < FISH; i++) spawnFish(i, rnd(-0.1f, 1.1f) * w);
            int[] greens = {0xFF2F8F5A, 0xFF3FA66A, 0xFF22704A, 0xFF4BB37A, 0xFF5A8F3A};
            for (int i = 0; i < WEEDS; i++) {
                wx[i] = (i + rnd(0.1f, 0.9f)) * w / WEEDS;
                wh[i] = rnd(0.22f, 0.5f) * h;
                wPhase[i] = rnd(0, 6.28f);
                wWidth[i] = rnd(10, 18) * k;
                wColor[i] = greens[random.nextInt(greens.length)];
            }
            for (int i = 0; i < BUBBLES; i++) spawnBubble(i, rnd(0, h));
            for (int i = 0; i < RAYS; i++) {
                rx[i] = rnd(0.05f, 0.95f) * w;
                rw[i] = rnd(60, 160) * k;
                rPhase[i] = rnd(0, 6.28f);
            }
            eye.setColor(0xFFF5F8FA);
            pupil.setColor(0xFF0A0E12);
            weed.setStyle(Paint.Style.STROKE);
            weed.setStrokeCap(Paint.Cap.ROUND);
            bubble.setStyle(Paint.Style.STROKE);
            bubble.setStrokeWidth(Math.max(1, 2 * k));
            shine.setColor(0xB3FFFFFF);
            fin.setColor(0x33FFFFFF);
            rayPaint.setShader(new LinearGradient(0, 0, 0, h * 0.85f, 0x2EBFEFFF, 0x00BFEFFF, Shader.TileMode.CLAMP));
            additive(rayPaint);
            bubble.setColor(Color.argb(150, 210, 240, 255));
        }

        private void spawnFish(int i, float x) {
            fDepth[i] = rnd(0, 1);                      // 0 = far, 1 = near
            fSize[i] = (40 + 70 * fDepth[i]) * k * rnd(0.85f, 1.2f);
            fDir[i] = random.nextBoolean() ? 1 : -1;
            fx[i] = x;
            fy[i] = rnd(0.12f, 0.78f) * h;
            fSpeed[i] = (50 + 90 * fDepth[i]) * k * rnd(0.8f, 1.3f);
            fPhase[i] = rnd(0, 6.28f);
            fBob[i] = rnd(10, 26) * k;
            fColor[i] = random.nextInt(COLORS.length);
        }

        private void spawnBubble(int i, float y) {
            bx[i] = random.nextFloat() < 0.6f ? (random.nextBoolean() ? 0.18f : 0.74f) * w + rnd(-14, 14) * k : rnd(0, w);
            by[i] = y;
            br[i] = rnd(3, 11) * k;
            bSpeed[i] = rnd(70, 150) * k;
            bPhase[i] = rnd(0, 6.28f);
        }

        @Override void draw(Canvas c, float t, float dt) {
            c.drawBitmap(background, 0, 0, photo);
            // Light rays from the surface, slowly breathing.
            for (int i = 0; i < RAYS; i++) {
                float sway = (float) Math.sin(t * 0.07 + rPhase[i]) * 60 * k, x = rx[i] + sway;
                ray.reset();
                ray.moveTo(x - rw[i] * 0.3f, 0);
                ray.lineTo(x + rw[i] * 0.3f, 0);
                ray.lineTo(x + rw[i] * 1.6f + 200 * k, h * 0.85f);
                ray.lineTo(x - rw[i] * 0.6f + 200 * k, h * 0.85f);
                ray.close();
                rayPaint.setAlpha(Math.round(255 * (0.55f + 0.45f * (float) Math.sin(t * 0.3 + rPhase[i]))));
                c.drawPath(ray, rayPaint);
            }
            for (int i = 0; i < FISH; i++) if (fDepth[i] < 0.5f) drawFish(c, i, t, dt);
            drawWeeds(c, t);
            for (int i = 0; i < FISH; i++) if (fDepth[i] >= 0.5f) drawFish(c, i, t, dt);
            for (int i = 0; i < BUBBLES; i++) {
                by[i] -= bSpeed[i] * dt;
                if (by[i] < -20 * k) spawnBubble(i, h * 0.9f + rnd(0, 60) * k);
                float x = bx[i] + (float) Math.sin(t * 2.2 + bPhase[i]) * 6 * k;
                c.drawCircle(x, by[i], br[i], bubble);
                c.drawCircle(x - br[i] * 0.35f, by[i] - br[i] * 0.35f, br[i] * 0.25f, shine);
            }
        }

        private void drawWeeds(Canvas c, float t) {
            float base = h * 0.9f;
            for (int i = 0; i < WEEDS; i++) {
                weed.setColor(wColor[i]);
                for (int strand = 0; strand < 3; strand++) {
                    float x = wx[i] + (strand - 1) * 18 * k, len = wh[i] * (1 - strand * 0.18f), seg = len / 8;
                    float px = x, py = base;
                    for (int s = 1; s <= 8; s++) {
                        float sway = (float) Math.sin(t * 0.9 + wPhase[i] + strand + s * 0.45) * 7 * k * s;
                        float nx = x + sway, ny = base - seg * s;
                        weed.setStrokeWidth(wWidth[i] * (1 - s / 10f));
                        c.drawLine(px, py, nx, ny, weed);
                        px = nx;
                        py = ny;
                    }
                }
            }
        }

        private void drawFish(Canvas c, int i, float t, float dt) {
            fx[i] += fDir[i] * fSpeed[i] * dt;
            float s = fSize[i];
            if (fDir[i] > 0 && fx[i] > w + 2 * s || fDir[i] < 0 && fx[i] < -2 * s) {
                spawnFish(i, 0);
                fx[i] = fDir[i] > 0 ? -2 * fSize[i] : w + 2 * fSize[i];
                return;
            }
            float y = fy[i] + (float) Math.sin(t * 0.6 + fPhase[i]) * fBob[i];
            float tilt = (float) Math.cos(t * 0.6 + fPhase[i]) * 6;
            // Far fish are smaller, slower and fainter, sinking into the blue.
            int alpha = Math.round(255 * (0.5f + 0.5f * fDepth[i]));
            Paint bodyPaint = bodies[fColor[i]], tailPaint = tails[fColor[i]];
            bodyPaint.setAlpha(alpha);
            tailPaint.setAlpha(alpha);
            eye.setAlpha(alpha);
            int save = c.save();
            c.translate(fx[i], y);
            c.scale(fDir[i] * s, s);
            c.rotate(-tilt * fDir[i]);
            // Tail: wags around the joint at the back of the body.
            int tailSave = c.save();
            c.rotate((float) Math.sin(t * 7 + fPhase[i]) * 16, -0.9f, 0);
            tail.reset();
            tail.moveTo(-0.85f, 0);
            tail.lineTo(-1.55f, -0.55f);
            tail.quadTo(-1.38f, 0, -1.55f, 0.55f);
            tail.close();
            c.drawPath(tail, tailPaint);
            c.restoreToCount(tailSave);
            body.reset();
            body.moveTo(1, 0);
            body.cubicTo(0.8f, -0.62f, -0.5f, -0.6f, -0.95f, 0);
            body.cubicTo(-0.5f, 0.6f, 0.8f, 0.62f, 1, 0);
            c.drawPath(body, bodyPaint);
            // A fin and the eye.
            tail.reset();
            tail.moveTo(-0.1f, -0.45f);
            tail.quadTo(0.1f, -0.85f, 0.35f, -0.42f);
            tail.close();
            c.drawPath(tail, fin);
            c.drawCircle(0.58f, -0.12f, 0.13f, eye);
            c.drawCircle(0.62f, -0.12f, 0.07f, pupil);
            c.restoreToCount(save);
        }
    }

    // MARK: - Fireplace

    /** A brick fireplace with logs and a living fire: flames, embers drifting up and the room lit by the glow. */
    static final class Fireplace extends Live {
        private static final int FLAMES = 150, EMBERS = 40;
        private final float[] px = new float[FLAMES], py = new float[FLAMES], pvx = new float[FLAMES], pvy = new float[FLAMES],
                page = new float[FLAMES], plife = new float[FLAMES], psize = new float[FLAMES], pseed = new float[FLAMES];
        private final float[] ex = new float[EMBERS], ey = new float[EMBERS], evy = new float[EMBERS], eage = new float[EMBERS],
                elife = new float[EMBERS], eseed = new float[EMBERS];
        private Bitmap core, mid, outer, warm;
        private final Paint add = new Paint(Paint.FILTER_BITMAP_FLAG), ember = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF();
        private float fireLeft, fireRight, fireBase;

        @Override void paintBackground(Canvas c) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            c.drawColor(0xFF140C0A);
            // Bricks.
            float bh = 56 * k, bw = 150 * k, mortar = 6 * k;
            for (int row = 0; row * bh < h; row++) {
                float off = row % 2 == 0 ? 0 : bw / 2;
                for (float x = -off; x < w; x += bw) {
                    int r = 70 + random.nextInt(30), g = 36 + random.nextInt(16), b = 28 + random.nextInt(12);
                    p.setColor(Color.rgb(r, g, b));
                    c.drawRoundRect(x + mortar / 2, row * bh + mortar / 2, x + bw - mortar / 2, (row + 1) * bh - mortar / 2, 4 * k, 4 * k, p);
                }
            }
            // The firebox: an arch into the dark.
            float left = w * 0.24f, right = w * 0.76f, top = h * 0.24f, bottom = h * 0.84f;
            fireLeft = w * 0.36f;
            fireRight = w * 0.64f;
            fireBase = h * 0.74f;
            Path box = new Path();
            box.moveTo(left, bottom);
            box.lineTo(left, top + (right - left) * 0.18f);
            box.quadTo(w / 2f, top - (right - left) * 0.08f, right, top + (right - left) * 0.18f);
            box.lineTo(right, bottom);
            box.close();
            Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(26 * k);
            frame.setColor(0xFF3A2A24);
            c.drawPath(box, frame);
            p.setShader(new LinearGradient(0, top, 0, bottom, 0xFF0A0605, 0xFF1E0F0A, Shader.TileMode.CLAMP));
            c.drawPath(box, p);
            // Soot fading up the back wall.
            p.setShader(new RadialGradient(w / 2f, bottom, (right - left) * 0.6f, 0x00000000, 0x99000000, Shader.TileMode.CLAMP));
            c.drawPath(box, p);
            // Hearth: a stone slab across the bottom.
            p.setShader(new LinearGradient(0, bottom, 0, h, 0xFF3A302C, 0xFF1A1412, Shader.TileMode.CLAMP));
            c.drawRect(0, bottom, w, h, p);
            p.setShader(null);
            p.setColor(0x33FFFFFF);
            c.drawRect(0, bottom, w, bottom + 3 * k, p);
            // Logs.
            drawLog(c, w * 0.34f, fireBase + 28 * k, w * 0.66f, fireBase + 6 * k, 34 * k);
            drawLog(c, w * 0.38f, fireBase + 2 * k, w * 0.6f, fireBase + 40 * k, 30 * k);
            drawLog(c, w * 0.42f, fireBase + 46 * k, w * 0.58f, fireBase + 46 * k, 26 * k);
            // The room darkens away from the fire.
            p.setShader(new RadialGradient(w / 2f, fireBase, w * 0.75f, 0x00000000, 0xCC000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }

        private void drawLog(Canvas c, float x1, float y1, float x2, float y2, float r) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(2 * r);
            p.setColor(0xFF3B2416);
            c.drawLine(x1, y1, x2, y2, p);
            p.setStrokeWidth(r * 0.5f);
            p.setColor(0x66FF7A2A);
            c.drawLine(x1, y1 - r * 0.6f, x2, y2 - r * 0.6f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF6A4A30);
            c.drawCircle(x2, y2, r * 0.92f, p);
            p.setColor(0xFF8A6440);
            c.drawCircle(x2, y2, r * 0.55f, p);
        }

        @Override void setup() {
            int s = Math.max(16, Math.round(128 * k));
            core = glow(s, 0xFFFFF2C2, 0xCCFFC24A);
            mid = glow(s, 0xE6FF9A2A, 0x99E2541A);
            outer = glow(s, 0x99C8361A, 0x4D5A1408);
            warm = glow(Math.max(16, Math.round(256 * k)), 0x66FF8A30, 0x26FF5A10);
            additive(add);
            for (int i = 0; i < FLAMES; i++) spawn(i, rnd(0, 1));
            for (int i = 0; i < EMBERS; i++) spawnEmber(i, rnd(0, 1));
        }

        private void spawn(int i, float age) {
            float u = random.nextFloat();
            // More flame in the middle of the logs.
            float x = fireLeft + (fireRight - fireLeft) * (0.5f + (u - 0.5f) * (0.6f + 0.4f * random.nextFloat()));
            px[i] = x;
            py[i] = fireBase + rnd(-6, 14) * k;
            pvx[i] = rnd(-14, 14) * k;
            pvy[i] = -rnd(150, 300) * k * (1.2f - Math.abs(u - 0.5f));
            plife[i] = rnd(0.55f, 1.25f);
            page[i] = age * plife[i];
            psize[i] = rnd(70, 130) * k;
            pseed[i] = rnd(0, 6.28f);
        }

        private void spawnEmber(int i, float age) {
            ex[i] = rnd(fireLeft, fireRight);
            ey[i] = fireBase - rnd(0, 60) * k;
            evy[i] = -rnd(60, 160) * k;
            elife[i] = rnd(2, 4.5f);
            eage[i] = age * elife[i];
            eseed[i] = rnd(0, 6.28f);
        }

        @Override void draw(Canvas c, float t, float dt) {
            c.drawBitmap(background, 0, 0, photo);
            // The glow on the room breathes with the fire.
            float flicker = 0.75f + 0.12f * (float) Math.sin(t * 7.1) + 0.08f * (float) Math.sin(t * 13.3 + 1) + 0.05f * (float) Math.sin(t * 2.3);
            add.setAlpha(Math.round(255 * flicker));
            float gr = w * 0.42f;
            dst.set(w / 2f - gr, fireBase - gr * 0.9f, w / 2f + gr, fireBase + gr * 1.1f);
            c.drawBitmap(warm, null, dst, add);
            for (int i = 0; i < FLAMES; i++) {
                page[i] += dt;
                if (page[i] >= plife[i]) {
                    spawn(i, 0);
                    continue;
                }
                float a = page[i] / plife[i];
                pvx[i] += (float) Math.sin(t * 3 + pseed[i]) * 40 * k * dt;
                px[i] += pvx[i] * dt;
                // Drawn in toward the middle as they rise, like a flame's tongue.
                px[i] += (w / 2f - px[i]) * 0.6f * dt;
                py[i] += pvy[i] * dt;
                float size = psize[i] * (1 - a * 0.75f);
                Bitmap sprite = a < 0.3f ? core : a < 0.65f ? mid : outer;
                add.setAlpha(Math.round(255 * Math.min(1, (1 - a) * 1.6f) * Math.min(1, a * 8 + 0.2f)));
                dst.set(px[i] - size / 2, py[i] - size * 0.7f, px[i] + size / 2, py[i] + size * 0.5f);
                c.drawBitmap(sprite, null, dst, add);
            }
            for (int i = 0; i < EMBERS; i++) {
                eage[i] += dt;
                if (eage[i] >= elife[i]) {
                    spawnEmber(i, 0);
                    continue;
                }
                float a = eage[i] / elife[i];
                ey[i] += evy[i] * dt;
                ex[i] += (float) Math.sin(t * 1.7 + eseed[i]) * 30 * k * dt;
                ember.setColor(Color.argb(Math.round(255 * (1 - a)), 255, 150 + Math.round(80 * (1 - a)), 60));
                c.drawCircle(ex[i], ey[i], (1.5f + 2 * (1 - a)) * k, ember);
            }
        }

        @Override void release() {
            super.release();
            for (Bitmap b : new Bitmap[] {core, mid, outer, warm}) if (b != null) b.recycle();
        }
    }

    // MARK: - Northern lights

    /** Green and violet curtains of light rippling over dark mountains, twinkling stars and now and then a shooting star. */
    static final class Aurora extends Live {
        private static final int STARS = 90, CURTAINS = 3, COLUMNS = 72;
        private final float[] sx = new float[STARS], sy = new float[STARS], sPhase = new float[STARS], sSize = new float[STARS];
        private final float[][] verts = new float[CURTAINS][(COLUMNS + 1) * 2 * 2];
        private final int[][] colors = new int[CURTAINS][(COLUMNS + 1) * 2 + 0];
        private final float[] cPhase = new float[CURTAINS], cSpeed = new float[CURTAINS], cHeight = new float[CURTAINS], cBase = new float[CURTAINS];
        private Bitmap[] strips = new Bitmap[CURTAINS];
        private final Paint add = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), star = new Paint(Paint.ANTI_ALIAS_FLAG),
                trail = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap mountains;
        private float shootAt = 8, shootX, shootY, shootDx, shootDy;

        @Override void paintBackground(Canvas c) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, h * 0.8f, new int[] {0xFF02030A, 0xFF060F22, 0xFF0B2533},
                    new float[] {0, 0.6f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            // Faint stars that don't twinkle.
            p.setShader(null);
            for (int i = 0; i < 380; i++) {
                p.setColor(Color.argb(Math.round(rnd(40, 150)), 220, 230, 255));
                c.drawCircle(rnd(0, w), rnd(0, h * 0.75f), rnd(0.6f, 1.5f) * k, p);
            }
        }

        @Override void setup() {
            // The mountains go on top of the aurora, so they are their own image.
            mountains = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(mountains);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            ridge(c, p, h * 0.68f, 120 * k, 0.0021f, 0xFF0B1820, 0xFF060D12);
            ridge(c, p, h * 0.78f, 90 * k, 0.0034f, 0xFF050A0E, 0xFF020406);
            // Pines along the front ridge.
            p.setShader(null);
            p.setColor(0xFF010203);
            Path tree = new Path();
            for (int i = 0; i < 70; i++) {
                float x = rnd(0, w), th = rnd(40, 110) * k, base = h * 0.8f + rnd(0, 0.2f) * h;
                tree.reset();
                tree.moveTo(x, base - th);
                tree.lineTo(x + th * 0.28f, base);
                tree.lineTo(x - th * 0.28f, base);
                tree.close();
                c.drawPath(tree, p);
            }
            for (int i = 0; i < STARS; i++) {
                sx[i] = rnd(0, w);
                sy[i] = rnd(0, h * 0.6f);
                sPhase[i] = rnd(0, 6.28f);
                sSize[i] = rnd(1.2f, 2.6f) * k;
            }
            int[][] tints = {{0xFF8CFFC0, 0xFF3CE08A, 0xFF6A3CFF}, {0xFF7AF5FF, 0xFF2CC8B0, 0xFF8A4CFF}, {0xFFB8FF9A, 0xFF50D86A, 0xFFC04CFF}};
            for (int i = 0; i < CURTAINS; i++) {
                strips[i] = strip(tints[i]);
                cPhase[i] = rnd(0, 6.28f);
                cSpeed[i] = rnd(0.6f, 1.2f);
                cHeight[i] = rnd(220, 340) * k;
                cBase[i] = h * rnd(0.42f, 0.56f);
            }
            additive(add);
            trail.setStrokeCap(Paint.Cap.ROUND);
        }

        private void ridge(Canvas c, Paint p, float base, float amp, float freq, int top, int bottom) {
            Path m = new Path();
            m.moveTo(0, h);
            float phase = rnd(0, 6.28f);
            for (int x = 0; x <= w; x += Math.max(4, w / 160)) {
                float y = base - amp * (0.6f * (float) Math.abs(Math.sin(x * freq + phase)) + 0.4f * (float) Math.abs(Math.sin(x * freq * 2.7f + phase * 2)));
                m.lineTo(x, y);
            }
            m.lineTo(w, h);
            m.close();
            p.setShader(new LinearGradient(0, base - amp, 0, h, top, bottom, Shader.TileMode.CLAMP));
            c.drawPath(m, p);
        }

        /** A curtain's colors from its bottom edge (bright) up to its top (violet, fading out). */
        private static Bitmap strip(int[] tint) {
            int n = 128;
            Bitmap b = Bitmap.createBitmap(1, n, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, n, new int[] {tint[2] & 0x00FFFFFF, Ui.alpha(tint[2], 0.25f), Ui.alpha(tint[1], 0.7f), Ui.alpha(tint[0], 1f), tint[0] & 0x00FFFFFF},
                    new float[] {0, 0.35f, 0.75f, 0.94f, 1}, Shader.TileMode.CLAMP));
            new Canvas(b).drawRect(0, 0, 1, n, p);
            return b;
        }

        @Override void draw(Canvas c, float t, float dt) {
            c.drawBitmap(background, 0, 0, photo);
            for (int i = 0; i < STARS; i++) {
                float a = 0.55f + 0.45f * (float) Math.sin(t * (1.2 + i % 5 * 0.3) + sPhase[i]);
                star.setColor(Color.argb(Math.round(230 * a), 235, 240, 255));
                c.drawCircle(sx[i], sy[i], sSize[i], star);
            }
            drawShootingStar(c, t);
            for (int i = 0; i < CURTAINS; i++) drawCurtain(c, i, t);
            c.drawBitmap(mountains, 0, 0, photo);
        }

        private void drawCurtain(Canvas c, int i, float t) {
            float[] v = verts[i];
            int[] col = colors[i];
            float step = (w + 200 * k) / COLUMNS, sp = cSpeed[i], ph = cPhase[i];
            for (int j = 0; j <= COLUMNS; j++) {
                float x = -100 * k + j * step;
                float base = cBase[i] + (float) (Math.sin(x * 0.0026 + t * 0.06 * sp + ph) * 70 * k + Math.sin(x * 0.009 - t * 0.17 * sp) * 22 * k);
                float height = cHeight[i] * (0.75f + 0.35f * (float) Math.sin(x * 0.005 + t * 0.11 * sp + ph * 2));
                float glow = 0.5f + 0.5f * (float) Math.sin(x * 0.0042 + t * 0.21 * sp + ph);
                glow = glow * glow * (0.55f + 0.45f * (float) Math.sin(t * 0.05 + ph));
                // A gentle sideways shimmer of the rays.
                float shimmer = (float) Math.sin(x * 0.05 + t * 1.3 * sp) * 8 * k;
                v[j * 2] = x + shimmer;
                v[j * 2 + 1] = base - height;
                int bottom = (COLUMNS + 1) * 2 + j * 2;
                v[bottom] = x;
                v[bottom + 1] = base;
                int a = Math.max(0, Math.min(255, Math.round(255 * glow)));
                int color = (a << 24) | 0xFFFFFF;
                col[j] = color;
                col[COLUMNS + 1 + j] = color;
            }
            c.drawBitmapMesh(strips[i], COLUMNS, 1, v, 0, col, 0, add);
        }

        private void drawShootingStar(Canvas c, float t) {
            if (t < shootAt) return;
            float age = t - shootAt;
            if (age == 0 || shootDx == 0) {
                shootX = rnd(0.1f, 0.7f) * w;
                shootY = rnd(0.05f, 0.3f) * h;
                shootDx = rnd(700, 1100) * k;
                shootDy = rnd(220, 380) * k;
            }
            if (age > 0.9f) {
                shootAt = t + rnd(14, 32);
                shootDx = 0;
                return;
            }
            float x = shootX + shootDx * age, y = shootY + shootDy * age, len = 0.18f;
            float a = Math.min(1, age * 6) * (1 - age / 0.9f);
            trail.setShader(null);
            trail.setStrokeWidth(2.4f * k);
            trail.setColor(Color.argb(Math.round(220 * a), 255, 255, 255));
            c.drawLine(x - shootDx * len, y - shootDy * len, x, y, trail);
        }

        @Override void release() {
            super.release();
            if (mountains != null) mountains.recycle();
            for (Bitmap b : strips) if (b != null) b.recycle();
        }
    }
}
