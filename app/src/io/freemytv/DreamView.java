package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver: real footage. The video clips (Videos: a fireplace, an aquarium... sent from a computer or
 * downloaded from config.properties) play muted and full screen, a new one every 10 minutes with a fade through
 * black; without clips, the photos (your own, else the picks' images) with a slow zoom and a crossfade every 20 s.
 * Bottom right, a small clock with the date and the weather, moved a few pixels every minute so nothing stays put
 * on the panel. Any key exits (the system's screensaver does that).
 */
final class DreamView extends FrameLayout implements Choreographer.FrameCallback, HomeData.Listener {
    private static final long CLIP_MS = 10 * 60_000L, FADE_MS = 1200, PHOTO_MS = 20_000, PHOTO_FADE = 2500;
    private static final float ZOOM = 0.08f;
    /** Where the rotation is, across screensavers: the next one starts on another clip or photo. */
    private static int nextClip, nextPhoto;

    private final HomeData data;
    private final Recs recs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextureView video;
    private final Chrome chrome;
    private MediaPlayer player;
    private Surface surface;
    private List<File> clips = new ArrayList<>();
    private boolean running, photoMode, decoding, odd;
    private int generation, videoW, videoH;
    private long shownAt, fadeOutAt;

    // Photos
    private Bitmap photo, incoming;
    private long photoAt, incomingAt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            Videos.download(getContext(), () -> handler.post(DreamView.this::clipsArrived));
            handler.postDelayed(this, 60_000);
        }
    };

    DreamView(Context c) {
        super(c);
        data = HomeData.get(c);
        recs = Recs.get(c);
        setBackgroundColor(0xFF000000);
        video = new TextureView(c);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                surface = new Surface(st);
                if (running && !photoMode) playNext();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { fit(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                release();
                if (surface != null) surface.release();
                surface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        chrome = new Chrome(c);
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(chrome, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        data.addListener(this);
        data.refreshIfStale();
        Videos.download(getContext(), () -> handler.post(this::clipsArrived));
        clips = Videos.files(getContext());
        photoMode = clips.isEmpty();
        video.setVisibility(photoMode ? INVISIBLE : VISIBLE);
        shownAt = SystemClock.uptimeMillis();
        fadeOutAt = 0;
        if (photoMode) nextPhoto();
        else if (surface != null) playNext();
        handler.postDelayed(tick, 60_000);
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        decoding = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        release();
        worker.shutdownNow();
        photo = incoming = null;
    }

    @Override public void onData() {
        chrome.minute = -1;
    }

    /** A download finished: if photos were showing, the clips take over. */
    private void clipsArrived() {
        if (!running) return;
        clips = Videos.files(getContext());
        if (photoMode && !clips.isEmpty()) {
            photoMode = false;
            photo = incoming = null;
            video.setVisibility(VISIBLE);
            if (surface != null) playNext();
        }
    }

    // MARK: - Video

    private void playNext() {
        release();
        if (clips.isEmpty() || surface == null) return;
        final File f = clips.get(Math.floorMod(nextClip++, clips.size()));
        final int gen = generation;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(f.getPath());
            mp.setSurface(surface);
            mp.setVolume(0, 0);
            mp.setLooping(clips.size() == 1);
            mp.setOnPreparedListener(p -> {
                if (gen != generation || p != player) return;
                videoW = p.getVideoWidth();
                videoH = p.getVideoHeight();
                fit();
                p.start();
                shownAt = SystemClock.uptimeMillis();
                fadeOutAt = 0;
            });
            mp.setOnVideoSizeChangedListener((p, vw, vh) -> {
                videoW = vw;
                videoH = vh;
                fit();
            });
            mp.setOnCompletionListener(p -> { if (p == player && fadeOutAt == 0) fadeOutAt = SystemClock.uptimeMillis(); });
            mp.setOnErrorListener((p, what, extra) -> {
                AppLog.w("screensaver video " + f.getName() + ": error " + what + "/" + extra);
                if (p == player) handler.post(this::skipBrokenClip);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            AppLog.w("screensaver video " + f.getName() + ": " + e.getMessage());
            handler.post(this::skipBrokenClip);
        }
    }

    /** A clip that won't play is left out this time; with none left, the photos. */
    private void skipBrokenClip() {
        if (!running) return;
        int at = Math.floorMod(nextClip - 1, Math.max(1, clips.size()));
        if (at < clips.size()) clips.remove(at);
        if (clips.isEmpty()) {
            release();
            photoMode = true;
            video.setVisibility(INVISIBLE);
            nextPhoto();
        } else {
            playNext();
        }
    }

    private void release() {
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try {
                p.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Fills the screen with the video, cropping the edges that don't fit (no black bars). */
    private void fit() {
        int vw = video.getWidth(), vh = video.getHeight();
        if (vw == 0 || vh == 0 || videoW == 0 || videoH == 0) return;
        float scale = Math.max(vw / (float) videoW, vh / (float) videoH);
        float sx = videoW * scale / vw, sy = videoH * scale / vh;
        Matrix m = new Matrix();
        m.setScale(sx, sy, vw / 2f, vh / 2f);
        video.setTransform(m);
    }

    // MARK: - Photos

    private List<File> photoFiles() {
        List<File> out = new ArrayList<>();
        if (Prefs.photos()) out.addAll(Photos.files(getContext()));
        if (out.isEmpty()) {
            for (Recs.Pick p : recs.showcase()) {
                File f = recs.readyImage(p);
                if (f != null) out.add(f);
            }
        }
        return out;
    }

    /** The next photo, a little larger than the screen (the zoom never enlarges it), decoded in the background. */
    private void nextPhoto() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || decoding) return;
        final List<File> files = photoFiles();
        if (files.isEmpty()) return;
        decoding = true;
        final int gen = generation, index = nextPhoto++;
        final int pw = Math.round(Math.min(w, Backdrop.W) * (1 + ZOOM)), ph = Math.round(pw * h / (float) w);
        worker.execute(() -> {
            Bitmap b = HomeView.decodeFile(files.get(Math.floorMod(index, files.size())), pw, ph, false);
            handler.post(() -> {
                decoding = false;
                if (gen != generation || !running || b == null) return;
                long now = SystemClock.uptimeMillis();
                if (photo == null) {
                    photo = b;
                    photoAt = now;
                } else {
                    if (incoming != null) {
                        photo = incoming;
                        photoAt = incomingAt;
                    }
                    incoming = b;
                    incomingAt = now;
                }
            });
        });
    }

    @Override public void doFrame(long nanos) {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        if (photoMode) {
            if (incoming != null && now - incomingAt >= PHOTO_FADE) {
                photo = incoming;
                photoAt = incomingAt;
                incoming = null;
            }
            long since = incoming != null ? now - incomingAt : now - photoAt;
            if (photo == null || (incoming == null && since >= PHOTO_MS - PHOTO_FADE)) nextPhoto();
        } else if (fadeOutAt == 0 && clips.size() > 1 && now - shownAt > CLIP_MS) {
            fadeOutAt = now;
        } else if (fadeOutAt != 0 && now - fadeOutAt >= FADE_MS) {
            fadeOutAt = 0;
            playNext();
        }
        // Older TVs: every other frame (30 fps) is plenty and spares their GPU.
        odd = !odd;
        if (!Ui.LEGACY || odd) chrome.invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** Drawn above the video: the photos (when there are no clips), the fades and the clock. */
    private final class Chrome extends View {
        private final Paint black = new Paint(), image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG), shade = new Paint();
        private final Matrix matrix = new Matrix();
        private Paint clockPaint, datePaint, weatherPaint;
        private String clock = "", date = "", weather = "";
        long minute = -1;
        private float k = 1;

        Chrome(Context c) {
            super(c);
            black.setColor(0xFF000000);
        }

        @Override protected void onSizeChanged(int width, int height, int oldw, int oldh) {
            k = width / 1920f;
            clockPaint = text(Ui.light, 112, 0xF2FFFFFF);
            clockPaint.setFontFeatureSettings("'tnum'");
            datePaint = text(Ui.medium, 30, 0xE6FFFFFF);
            weatherPaint = text(Ui.regular, 28, 0xCCFFFFFF);
            // A soft dark glow behind the clock, so it reads on any picture.
            shade.setShader(new RadialGradient(width * 0.86f, height * 0.86f, width * 0.32f, 0x73000000, 0x00000000, Shader.TileMode.CLAMP));
            minute = -1;
            if (running && photoMode && photo == null) nextPhoto();
        }

        private Paint text(android.graphics.Typeface face, float size, int color) {
            Paint p = Ui.text(face, size * k, color);
            p.setTextAlign(Paint.Align.RIGHT);
            p.setShadowLayer(14 * k, 0, 2 * k, 0x99000000);
            return p;
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            long now = SystemClock.uptimeMillis();
            if (photoMode) {
                if (photo == null) {
                    c.drawColor(0xFF000000);
                } else {
                    drawPhoto(c, photo, photoAt, now, 1);
                    if (incoming != null) drawPhoto(c, incoming, incomingAt, now, Ui.clamp((now - incomingAt) / (float) PHOTO_FADE));
                }
            } else {
                // Fade in after a clip starts, out before the next.
                float dark = player == null ? 1 : 1 - Ui.clamp((now - shownAt) / (float) FADE_MS);
                if (fadeOutAt != 0) dark = Math.max(dark, Ui.clamp((now - fadeOutAt) / (float) FADE_MS));
                if (dark > 0.002f) {
                    black.setAlpha(Math.round(255 * dark));
                    c.drawRect(0, 0, w, h, black);
                }
            }
            c.drawRect(0, 0, w, h, shade);
            drawClock(c);
        }

        /** The slow zoom: in on even photos, out on odd ones, over the time each one shows. */
        private void drawPhoto(Canvas c, Bitmap b, long at, long now, float alpha) {
            int w = getWidth(), h = getHeight();
            float t = Ui.clamp((now - at) / (float) (PHOTO_MS + PHOTO_FADE));
            boolean in = (at / 1000) % 2 == 0;
            float base = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            float s = base * (1 + ZOOM * (in ? t : 1 - t));
            matrix.setScale(s, s);
            matrix.postTranslate((w - b.getWidth() * s) / 2, (h - b.getHeight() * s) / 2);
            image.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(b, matrix, image);
        }

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
}
