package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver: live scenes (LiveScenes: an aquarium, a fireplace, northern lights), a new one every 12 minutes
 * with a fade through black, and a small clock with the date and the weather in a corner. The clock drifts a little
 * every minute so nothing stays put on the panel. Any key exits (the system's screensaver does that).
 * Older TVs (Android 8 to 11) draw at 30 frames a second.
 */
final class DreamView extends View implements Choreographer.FrameCallback, HomeData.Listener {
    private static final long SCENE_MS = 12 * 60_000L, FADE_MS = 1500;
    /** Where the rotation is, across screensavers: the next one starts on another scene. */
    private static int nextScene = new Random().nextInt(LiveScenes.COUNT);

    private final HomeData data;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Paint black = new Paint();
    private LiveScenes.Live scene;
    private boolean running, loading, odd;
    private int generation, w, h;
    private float k = 1, time;
    private long lastDraw, sceneAt, fadeOutAt;
    private Paint clockPaint, datePaint, weatherPaint;
    private String clock = "", date = "", weather = "";
    private long minute = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            handler.postDelayed(this, 60_000);
        }
    };

    DreamView(Context c) {
        super(c);
        data = HomeData.get(c);
        black.setColor(0xFF000000);
    }

    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        lastDraw = 0;
        fadeOutAt = 0;
        data.addListener(this);
        data.refreshIfStale();
        handler.postDelayed(tick, 60_000);
        if (scene == null) loadScene();
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        loading = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        final LiveScenes.Live gone = scene;
        scene = null;
        // After the last frame: the scene's images are still on the screen until then.
        if (gone != null) handler.post(gone::release);
        worker.shutdownNow();
    }

    @Override public void onData() {
        minute = -1;
    }

    @Override protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        w = width;
        h = height;
        k = width / 1920f;
        clockPaint = text(Ui.light, 112, 0xF2FFFFFF);
        clockPaint.setFontFeatureSettings("'tnum'");
        datePaint = text(Ui.medium, 30, 0xD9FFFFFF);
        weatherPaint = text(Ui.regular, 28, 0xB3FFFFFF);
        minute = -1;
        if (running) {
            LiveScenes.Live old = scene;
            scene = null;
            if (old != null) handler.post(old::release);
            loading = false;
            loadScene();
        }
    }

    private Paint text(android.graphics.Typeface face, float size, int color) {
        Paint p = Ui.text(face, size * k, color);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setShadowLayer(14 * k, 0, 2 * k, 0x99000000);
        return p;
    }

    /** Builds the next scene's still parts in the background; it fades in once ready. */
    private void loadScene() {
        if (w == 0 || loading) return;
        loading = true;
        final int gen = generation, index = nextScene++, width = w, height = h;
        worker.execute(() -> {
            LiveScenes.Live s = LiveScenes.make(index);
            try {
                s.prepare(width, height);
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("screensaver scene: " + e.getMessage());
                s.release();
                s = null;
            }
            final LiveScenes.Live ready = s;
            handler.post(() -> {
                if (gen != generation || !running) {
                    if (ready != null) ready.release();
                    return;
                }
                loading = false;
                LiveScenes.Live old = scene;
                scene = ready;
                if (old != null) old.release();
                sceneAt = SystemClock.uptimeMillis();
                fadeOutAt = 0;
                time = 0;
            });
        });
    }

    @Override public void doFrame(long nanos) {
        if (!running) return;
        // Older TVs: every other frame (30 fps) is plenty for these scenes and spares their GPU.
        odd = !odd;
        if (!Ui.LEGACY || odd) invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDraw(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float dt = lastDraw == 0 ? 1 / 60f : Math.min(0.1f, (up - lastDraw) / 1000f);
        lastDraw = up;
        time += dt;
        if (scene == null || scene.background == null) {
            c.drawColor(0xFF000000);
        } else {
            scene.draw(c, time, dt);
            // A fade in at the start, a fade out before the next scene.
            if (fadeOutAt == 0 && up - sceneAt > SCENE_MS) fadeOutAt = up;
            float dark = 1 - Ui.clamp((up - sceneAt) / (float) FADE_MS);
            if (fadeOutAt != 0) {
                dark = Math.max(dark, Ui.clamp((up - fadeOutAt) / (float) FADE_MS));
                if (up - fadeOutAt >= FADE_MS && !loading) loadScene();
            }
            if (dark > 0.001f) {
                black.setAlpha(Math.round(255 * dark));
                c.drawRect(0, 0, w, h, black);
            }
        }
        drawClock(c);
    }

    /** Bottom right: the time, the date and the weather, moved a few pixels every minute. */
    private void drawClock(Canvas c) {
        if (clockPaint == null) return;
        long now = System.currentTimeMillis();
        if (now / 60_000 != minute) {
            minute = now / 60_000;
            clock = Ui.clock(now);
            String d = Ui.date(now);
            date = d.isEmpty() ? d : d.substring(0, 1).toUpperCase(Ui.locale()) + d.substring(1);
            HomeData.Weather wx = data.weather;
            weather = !Prefs.weather() || Double.isNaN(wx.temp) ? ""
                    : HomeData.degrees(wx.temp) + "  ·  " + HomeData.condition(wx.code, wx.isDay);
        }
        float dx = ((minute * 37) % 41 - 20) * k, dy = ((minute * 23) % 31 - 15) * k;
        float right = 1824 * k + dx, base = 960 * k + dy;
        c.drawText(clock, right, base - 70 * k, clockPaint);
        c.drawText(date, right, base - 20 * k, datePaint);
        if (!weather.isEmpty()) c.drawText(weather, right, base + 22 * k, weatherPaint);
    }
}
