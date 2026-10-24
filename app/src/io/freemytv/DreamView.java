package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.View;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver, in the same style as the home screen:
 *  - the recommendations' photos in full screen, one every 30 s, with a slow zoom and pan (the "Ken Burns" effect of
 *    the Apple TV photos) and a crossfade between them; each photo is loaded 10% larger than the screen, so the zoom
 *    never upscales it;
 *  - top right, like the iPhone lock screen: the date with the weather, the big Liquid Glass clock (the photo shows
 *    through the digits, bent at the edges; Shaders.GLASS_TEXT) and the compact prices;
 *  - bottom left, the photo's caption: the time-of-day label ("TONIGHT"), the title, and genre · runtime · service.
 * Dark scrims only where there is text, stronger where the photo is bright. Any key exits (as on Apple TV).
 * Without photos (no TMDB key, or before the first fetch): the big clock over the night aurora.
 */
final class DreamView extends View implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    private static final long SHOW_MS = 30_000, FADE_MS = 3_000, FIRST_MS = 1_600;
    private static final float ZOOM = 0.1f;
    /** Photos 10% larger than the screen (the zoom goes up to 1.1 without upscaling). */
    private static final int PW = 2112, PH = 1188;
    private static final float MARGIN = 80, RIGHT = 1840, TITLE_W = 900, META_BASE = 1008;
    private static final float DATE_BASE = 92, CLOCK_TOP = 120, CLOCK_SIZE = 200, QUOTES_GAP = 52;
    private static final double DRIFT_PERIOD_S = 600;

    /** One photo on screen: the image, its motion (zooming in or out, toward a corner) and its caption. */
    private static final class Slide {
        final Recs.Pick pick;
        final Bitmap image, frost;
        final BitmapShader shader;    // the frosted version, for the clock's glass
        final float lightCaption, lightClock;
        final boolean zoomIn;
        final float panX, panY;
        final Recording caption = Recording.create("caption");
        long startedAt;

        Slide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock, int n) {
            this.pick = pick;
            this.image = image;
            this.frost = frost;
            shader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Frost.filter(shader, BitmapShader.FILTER_MODE_LINEAR);
            this.lightCaption = lightCaption;
            this.lightClock = lightClock;
            zoomIn = n % 2 == 0;
            // Toward one of the corners, alternating, without repeating the previous one.
            panX = (n / 2) % 2 == 0 ? 0.8f : -0.8f;
            panY = n % 3 == 0 ? -0.6f : 0.6f;
        }
    }

    /** The glass clock's digits, rendered once a minute in the background. */
    private static final class Glass {
        final String text;
        final Bitmap slope, cover, shadow;
        final int x, y;
        final float inkTop, inkHeight;

        Glass(String text, Bitmap slope, Bitmap cover, Bitmap shadow, int x, int y, float inkTop, float inkHeight) {
            this.text = text;
            this.slope = slope;
            this.cover = cover;
            this.shadow = shadow;
            this.x = x;
            this.y = y;
            this.inkTop = inkTop;
            this.inkHeight = inkHeight;
        }
    }

    private final Scene scene = new Scene(false);
    private final HomeData data;
    private final Recs recs;
    private final Scenery scenery;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Recording content = Recording.create("clock");     // without photos: the clock over the aurora
    private final Recording chrome = Recording.create("corner");     // date, weather, prices and the clock's shadow
    private final float timeOffset = (float) (Math.random() * 300);
    private final Matrix matrix = new Matrix();
    private final float[] placeA = new float[3], placeB = new float[3];
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint black = new Paint();
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glassPaint = new Paint();
    private final Set<String> failed = new HashSet<>();     // Recs.key: a movie and a series can have the same ID
    private RuntimeShader glassShader;
    private boolean running, dirty = true, chromeDirty = true;
    private boolean fallback;       // the aurora with the clock (no photos)
    private boolean decoding;
    private int generation, slides;
    private Slide current, incoming, prepared;
    private Glass glass;
    private String glassFor = "";
    private long startedAt, firstAt, lastFrame, lastSky;
    private int w, h;
    private float k = 1;
    private long minute = -1;
    private String clock = "", date = "";
    private Paint datePaint, clockPaint, weatherPaint, weatherDim, quotePaint, stalePaint;
    private Paint cornerDate, cornerQuote, cornerStale, cornerClock, labelPaint, metaPaint;
    private TextPaint titlePaint;
    // Music playing (MediaNow): bottom left, instead of the photo's caption, the cover, the song and the equalizer
    private final MediaNow media;
    private static final int WIDE_BARS = 28;      // a wide row, much more than the home screen's four
    private final Beat beat = new Beat(WIDE_BARS);
    private Paint songPaint;
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint coverShade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix coverMatrix = new Matrix();
    private final RectF coverRect = new RectF(), barRect = new RectF();
    private BitmapShader coverShader, prevShader;
    private Bitmap coverFor, coverPrev;     // the cover shown and, while it fades in, the one before
    private long coverAt;
    private static final long COVER_FADE_MS = 700, BG_FADE_MS = 1500;
    private Bitmap musicBgOld;              // the previous artist's background, while the new one fades in
    private long bgSwapAt;
    private String songFor, songText, artistFor, artistText;
    private final float[] bars = new float[WIDE_BARS];
    private long barsAt;
    private Bitmap musicBg, musicFrost;
    private BitmapShader musicFrostShader;
    private boolean boundMusic;     // the glass reads the music's background: rebind the photos when it ends
    private String musicBgFor;  // the artist the background is of
    private float musicAlpha;
    private long musicAt;
    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final RectF bgRect = new RectF();
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint timePaint;
    private long elapsedFor = -1, durationFor = -1;
    private String elapsedText = "", durationText = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            if (Prefs.saverPhotos().equals("shows")) recs.prefetchAll();
            else scenery.prefetch();
            dirty = true;
            chromeDirty = true;
            prepareNext();
            handler.postDelayed(this, 60_000);
        }
    };

    DreamView(Context c) {
        super(c);
        data = HomeData.get(c);
        recs = Recs.get(c);
        scenery = Scenery.get(c);
        Shaders.dream(scene.palette);
        scene.setMotion(0.4f, 0.32f);
        scene.applyPalette();
        content.setUseCompositingLayer(true, null);
        try {
            glassShader = new RuntimeShader(Shaders.GLASS_TEXT);
            glassPaint.setShader(glassShader);
        } catch (RuntimeException | LinkageError e) {   // Android 9 to 12: no AGSL
            AppLog.w("glass clock unavailable: " + e.getMessage());
            glassShader = null;
        }
        black.setColor(0xFF000000);
        shadowPaint.setColor(0xFF000000);
        media = new MediaNow(c, this::mediaChanged);
        barPaint.setColor(0xEBFFFFFF);
    }

    private void mediaChanged() {
        if (running && media.shown() && media.playing) beat.start();
        else beat.stop();
        final String artist = media.shown() ? ArtistPhoto.main(media.artist) : "";
        if (!artist.isEmpty() && !artist.equals(musicBgFor)) {
            // The background: a photo of the artist (Spotify keeps its own backdrops inside the app), the same while
            // the same artist plays; without one, the photos go on
            musicBgFor = artist;
            final Context context = getContext().getApplicationContext();
            worker.execute(() -> {
                Bitmap photo = ArtistPhoto.get(context, artist);
                final Bitmap[] bg = photo == null ? null : artistBackground(photo);
                handler.post(() -> {
                    if (!artist.equals(musicBgFor)) {
                        if (bg != null) for (Bitmap b : bg) b.recycle();
                        return;
                    }
                    final Bitmap oldFrost = musicFrost;
                    // The new artist's background cross-fades over the previous one (kept until then)
                    if (musicBgOld != null) {
                        final Bitmap older = musicBgOld;
                        handler.postDelayed(older::recycle, 1000);
                    }
                    musicBgOld = musicBg;
                    bgSwapAt = SystemClock.uptimeMillis();
                    musicBg = bg == null ? null : bg[0];
                    musicFrost = bg == null ? null : bg[1];
                    musicFrostShader = null;
                    if (musicFrost != null) {
                        musicFrostShader = new BitmapShader(musicFrost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                        Frost.filter(musicFrostShader, BitmapShader.FILTER_MODE_LINEAR);
                    }
                    if (oldFrost != null) handler.postDelayed(oldFrost::recycle, 1000);
                    invalidate();
                });
            });
        }
        invalidate();
    }

    /**
     * An artist's photo as the screen's background: filling it (a square photo keeps the face, near its top, in view),
     * darkened where the text goes (the bottom left, and the top right for the clock). Returns {background (on the
     * GPU), frosted (what the glass clock shows through it)}.
     */
    private static Bitmap[] artistBackground(Bitmap photo) {
        int w = 1920, h = 1080;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float scale = Math.max(w / (float) photo.getWidth(), h / (float) photo.getHeight());
        float pw = photo.getWidth() * scale, ph = photo.getHeight() * scale;
        float y = ph > h * 1.05f ? -(ph - h) * 0.22f : (h - ph) / 2;
        c.drawBitmap(photo, null, new RectF((w - pw) / 2, y, (w + pw) / 2, y + ph), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        photo.recycle();
        // How bright it is where the music corner and the clock sit: a bright photo gets darker there (like the photos)
        float lightCorner = Backdrop.luminance(out, 0, 0.55f, 0.65f, 0.98f);
        float lightClock = Backdrop.luminance(out, 0.62f, 0.04f, 0.96f, 0.36f);
        Paint p = new Paint(Paint.DITHER_FLAG);
        c.drawColor(black(0.2f + 0.25f * Math.max(0, Math.max(lightCorner, lightClock) - 0.35f)));
        float corner = Math.min(0.92f, 0.78f + 0.4f * Math.max(0, lightCorner - 0.3f));
        p.setShader(new LinearGradient(0, h, 0, h * 0.35f, new int[] {black(corner), black(corner * 0.6f), 0},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.35f, w, h, p);
        p.setShader(new LinearGradient(0, 0, w * 0.6f, 0, new int[] {black(0.45f), 0}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w * 0.6f, h, p);
        // Behind the clock: a soft dark patch, stronger on a bright photo (the glass digits need contrast)
        float clockDark = Math.min(0.75f, 0.42f + 0.6f * Math.max(0, lightClock - 0.3f));
        float cx = w * 0.86f, cy = h * 0.15f;
        RadialGradient patch = new RadialGradient(cx, cy, w * 0.36f, new int[] {black(clockDark), black(clockDark * 0.6f), black(clockDark * 0.15f), 0},
                new float[] {0, 0.42f, 0.75f, 1}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1, 0.72f, cx, cy);
        patch.setLocalMatrix(m);
        p.setShader(patch);
        c.drawRect(w * 0.45f, 0, w, cy + w * 0.36f * 0.72f, p);
        Bitmap frost = Backdrop.frost(out);
        return new Bitmap[] {Backdrop.gpu(out), frost};
    }

    /** The music background over the photos, fading in and out with the music (the glass clock follows, drawGlass). */
    private void drawMusicBackground(Canvas c, long up) {
        float target = media.shown() && musicBg != null ? 1 : 0;
        float dt = musicAt == 0 ? 0 : Math.min(0.1f, (up - musicAt) / 1000f);
        musicAt = up;
        musicAlpha = dt == 0 ? target : musicAlpha + (target - musicAlpha) * Math.min(1, dt * 2.5f);
        if (musicAlpha < 0.004f || musicBg == null) return;
        bgRect.set(0, 0, w, h);
        // Another artist: the new background fades in over the previous one
        float swap = musicBgOld == null ? 1 : fadeIn(up - bgSwapAt, BG_FADE_MS);
        if (musicBgOld != null && swap < 1) {
            bgPaint.setAlpha(Math.round(255 * musicAlpha));
            c.drawBitmap(musicBgOld, null, bgRect, bgPaint);
        } else if (musicBgOld != null) {
            final Bitmap gone = musicBgOld;     // once the last frame that used it is drawn
            musicBgOld = null;
            handler.postDelayed(gone::recycle, 1000);
        }
        bgPaint.setAlpha(Math.round(255 * musicAlpha * swap));
        c.drawBitmap(musicBg, null, bgRect, bgPaint);
    }


    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        startedAt = clock();
        lastFrame = 0;
        failed.clear();
        applyClockSize();
        fallback = photos().isEmpty();
        data.addListener(this);
        recs.addListener(this);
        scenery.addListener(this);     // a photo downloaded: the next one can be prepared
        data.refreshIfStale();
        recs.refreshIfStale();
        recs.prefetchAll();
        prepareNext();
        handler.postDelayed(tick, 60_000);
        media.start();
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        decoding = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        recs.removeListener(this);
        scenery.removeListener(this);
        media.stop();
        beat.stop();
        // The photos stay in GPU memory until the garbage collector runs: release them now (after the last frame).
        final java.util.Set<Bitmap> gone = new java.util.HashSet<>();
        for (Slide s : new Slide[] {current, incoming, prepared}) {
            if (s == null) continue;
            gone.add(s.image);
            gone.add(s.frost);
        }
        if (!gone.isEmpty()) handler.postDelayed(() -> { for (Bitmap b : gone) b.recycle(); }, 1000);
        current = incoming = prepared = null;
        glass = null;
        slides = 0;
        glassFor = "";
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        worker.shutdownNow();
    }

    @Override public void onData() {
        dirty = true;
        chromeDirty = true;
        invalidate();
    }

    @Override public void onRecs() {
        if (running) prepareNext();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        scene.setSize(w, h);
        content.setPosition(0, 0, w, h);
        chrome.setPosition(0, 0, w, h);
        // Without photos (the clock over the aurora).
        datePaint = Ui.text(Ui.semibold, 36 * k, 0xD9FFFFFF);
        datePaint.setTextAlign(Paint.Align.CENTER);
        clockPaint = Ui.numbers(Ui.thin, 280 * k, 0xF2FFFFFF);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        clockPaint.setLetterSpacing(-0.01f);
        weatherPaint = Ui.numbers(Ui.medium, 30 * k, 0xC7FFFFFF);
        weatherDim = Ui.numbers(Ui.regular, 28 * k, 0x80FFFFFF);
        quotePaint = Ui.numbers(Ui.medium, 24 * k, 0x99FFFFFF);
        stalePaint = Ui.text(Ui.regular, 20 * k, 0x59FFFFFF);
        stalePaint.setTextAlign(Paint.Align.CENTER);
        // With photos: the corner (date, clock, prices) and the caption.
        cornerDate = Ui.numbers(Ui.semibold, 30 * k, 0xEBFFFFFF);
        cornerQuote = Ui.numbers(Ui.regular, 23 * k, 0xC7FFFFFF);
        cornerStale = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cornerClock = Ui.numbers(Ui.displaySemibold, CLOCK_SIZE * k, 0xF2FFFFFF);
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xB8FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xC7FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        songPaint = Ui.text(Ui.displayMedium, 54 * k, 0xFFFFFFFF);
        timePaint = Ui.numbers(Ui.medium, 26 * k, 0xD9FFFFFF);
        for (Paint p : new Paint[] {cornerDate, cornerQuote, cornerStale, cornerClock, labelPaint, titlePaint, metaPaint, songPaint, timePaint}) {
            p.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        }
        coverShade.setColor(0x80000000);
        coverShade.setMaskFilter(new android.graphics.BlurMaskFilter(26 * k, android.graphics.BlurMaskFilter.Blur.NORMAL));
        songFor = artistFor = null;
        glassFor = "";
        dirty = true;
        chromeDirty = true;
        for (Slide s : new Slide[] {current, incoming, prepared}) if (s != null) recordCaption(s);
        clockSizeFor = "";
        applyClockSize();
    }

    private String clockSizeFor = "";

    /** The clocks at the size picked in Settings > Screensaver clock (large: the size it always had). */
    private void applyClockSize() {
        String size = Prefs.clockSize();
        if (w == 0 || size.equals(clockSizeFor)) return;
        clockSizeFor = size;
        float scale = size.equals("large") ? 1f : size.equals("medium") ? 0.8f : 0.6f;
        cornerClock = Ui.numbers(Ui.displaySemibold, CLOCK_SIZE * scale * k, 0xF2FFFFFF);
        cornerClock.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        clockPaint = Ui.numbers(Ui.thin, 280 * scale * k, 0xF2FFFFFF);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        clockPaint.setLetterSpacing(-0.01f);
        glassFor = "";      // the glass digits are made again at this size
        dirty = true;
        chromeDirty = true;
    }

    // MARK: - Photos

    /** Loads the next photo in the background (one at a time); once ready, it comes in at the right moment. */
    /** The photos to cycle (Settings > Screensaver photos): the movies and shows picked for you, or Scenery's. */
    private List<Recs.Pick> photos() {
        return Prefs.saverPhotos().equals("shows") ? recs.showcase() : scenery.showcase();
    }

    private File photoFile(Recs.Pick p) {
        return Scenery.isScenery(p) ? scenery.readyImage(p) : recs.readyImage(p);
    }

    private void prepareNext() {
        if (!running || decoding || prepared != null) return;
        List<Recs.Pick> list = photos();
        if (list.isEmpty()) return;
        Slide last = incoming != null ? incoming : current;
        int start = 0;
        if (last != null) {
            for (int i = 0; i < list.size(); i++) {
                if (Recs.key(list.get(i)).equals(Recs.key(last.pick))) {
                    start = i + 1;
                    break;
                }
            }
        }
        Recs.Pick pick = null;
        for (int i = 0; i < list.size() && pick == null; i++) {
            Recs.Pick p = list.get((start + i) % list.size());
            if (!failed.contains(Recs.key(p))) pick = p;
        }
        if (pick == null) {
            if (current == null) fallback = true;
            return;
        }
        if (last != null && Recs.key(pick).equals(Recs.key(last.pick))) {
            // Only one photo: the same one again, with a different motion.
            prepared = newSlide(pick, last.image, last.frost, last.lightCaption, last.lightClock);
            return;
        }
        File file = photoFile(pick);
        if (file == null) {
            failed.add(Recs.key(pick));   // the image is gone from disk: try the next one
            prepareNext();
            return;
        }
        decoding = true;
        final int gen = generation;
        final Recs.Pick chosen = pick;
        worker.execute(() -> {
            Bitmap[] images = null;
            float[] light = new float[2];
            try {
                images = Backdrop.photo(file, PW, PH, light);
                images[0] = Backdrop.gpu(withScrims(images[0], light[0], light[1]));
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("screensaver: photo for “" + chosen.title + "”: " + e.getMessage());
            }
            final Bitmap[] ready = images;
            handler.post(() -> {
                if (gen != generation) {
                    // The screensaver closed while the photo was loading: free the memory right away.
                    if (ready != null) for (Bitmap b : ready) b.recycle();
                    return;
                }
                decoding = false;
                if (ready == null) {
                    failed.add(Recs.key(chosen));
                    prepareNext();
                    return;
                }
                prepared = newSlide(chosen, ready[0], ready[1], light[0], light[1]);
                invalidate();
            });
        });
    }

    private Slide newSlide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock) {
        Slide s = new Slide(pick, image, frost, lightCaption, lightClock, slides++);
        if (w > 0) recordCaption(s);
        return s;
    }

    /** The prepared photo goes on screen when its turn comes (the first one right away; the others every 30 s). */
    private void advance(long up) {
        if (current == null) {
            if (prepared == null) return;
            current = prepared;
            prepared = null;
            current.startedAt = up;
            firstAt = up;
            bindPhotos();
            prepareNext();
            return;
        }
        if (incoming == null && prepared != null && up >= current.startedAt + SHOW_MS) {
            incoming = prepared;
            prepared = null;
            incoming.startedAt = up;
            bindPhotos();
            return;
        }
        if (incoming != null && up >= incoming.startedAt + FADE_MS) {
            Slide old = current;
            current = incoming;
            incoming = null;
            bindPhotos();
            if (old.image != current.image) {
                // Once the last frame that uses them has been drawn.
                Bitmap gone = old.image, goneFrost = old.frost;
                handler.postDelayed(() -> {
                    gone.recycle();
                    goneFrost.recycle();
                }, 1000);
            }
            prepareNext();
        }
    }

    /** The photos the clock's glass shows (the current one and, during a crossfade, the incoming one). */
    private void bindPhotos() {
        if (glassShader == null || current == null) return;
        glassShader.setInputShader("imageA", current.shader);
        glassShader.setInputShader("imageB", incoming != null ? incoming.shader : current.shader);
    }

    /** Photo position and scale now: zoom from 1 to 1.1 (or back) and a pan toward a corner, never showing an edge. */
    private void place(Slide s, long up, float[] out) {
        float u = Ui.clamp((up - s.startedAt) / (float) (SHOW_MS + FADE_MS));
        float f = s.zoomIn ? u : 1 - u;
        float z = 1 + ZOOM * f;
        float rx = w * (z - 1) / 2, ry = h * (z - 1) / 2;
        out[0] = rx * (s.panX * f - 1);
        out[1] = ry * (s.panY * f - 1);
        out[2] = (float) w / s.image.getWidth() * z;
    }

    private void drawPhoto(Canvas c, Slide s, float[] place, float alpha) {
        if (alpha <= 0.004f) return;
        matrix.setScale(place[2], place[2]);
        matrix.postTranslate(place[0], place[1]);
        photoPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(s.image, matrix, photoPaint);
    }

    // MARK: - Frames: 20 per second, locked to every 3rd display frame (60 Hz)
    // Motion is slow (under half a pixel per frame). Measured on the TV: ~4 ms of GPU per frame of ours, but drawing
    // every frame, it only reaches ~50 fps (the system also composites the UI on the GPU); at 20 there's room to spare.

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (frameTimeNanos - lastFrame >= 45_000_000L) {
            lastFrame = frameTimeNanos;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** The clock that drives the animations. */
    private long clock() {
        long up = SystemClock.uptimeMillis();
        return up;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (w == 0) return;
        long up = clock();
        long now = System.currentTimeMillis();
        if (now / 60_000 != minute) {
            minute = now / 60_000;
            clock = Ui.clock(now);
            date = Ui.date(now);
            dirty = true;
            chromeDirty = true;
        }
        if (running) advance(up);
        if (!glassFor.equals(clock)) buildGlass(clock);

        float first = current == null ? 0 : fadeIn(up - firstAt, FIRST_MS);
        if (current == null || first < 1) {
            if (fallback) drawFallback(canvas, up, current == null ? 1 : 1 - first);
            else canvas.drawRect(0, 0, w, h, black);
        }
        if (current == null) {
            // No photos yet: with music, its background, the corner and the glass clock over it
            drawMusicBackground(canvas, up);
            if (musicAlpha > 0.004f) {
                if (chromeDirty) recordChrome();
                chrome.setAlpha(musicAlpha);
                chrome.draw(canvas);
                drawGlass(canvas, musicAlpha, 0);
                boundMusic = true;
            }
            drawMusic(canvas, up);
            return;
        }
        if (fallback && first >= 1) fallback = false;

        place(current, up, placeA);
        float e = incoming == null ? 0 : fadeIn(up - incoming.startedAt, FADE_MS);
        drawPhoto(canvas, current, placeA, first);
        if (incoming != null) {
            place(incoming, up, placeB);
            drawPhoto(canvas, incoming, placeB, e);
        }

        drawMusicBackground(canvas, up);
        if (chromeDirty) recordChrome();
        chrome.setAlpha(first);
        chrome.draw(canvas);
        drawGlass(canvas, first, e);
        if (media.shown()) drawMusic(canvas, up);
        else drawCaptions(canvas, up);
    }

    /**
     * Music playing: bottom left, where the photo's caption goes, the cover (rounded, with a shadow), and to its right
     * the song, the artist and the equalizer (following the sound, Beat; resting when paused).
     */
    private void drawMusic(Canvas c, long up) {
        if (!media.shown()) return;
        // A large cover; beside it, all within its height: the song and the artist, the equalizer, then the progress
        float side = 380 * k, left = MARGIN * k, bottom = (META_BASE + 10) * k, top = bottom - side;
        float tx = left + side + 48 * k;
        // The cover: the last song's stays until the new one has downloaded (MediaNow), then the new one cross-fades
        // over it as soon as it's here (blanking it between songs and waiting for the "ready" check made the change
        // late and jumpy; that check only decides when the screensaver may start)
        Bitmap art = media.art;
        if (art != coverFor) {
            coverPrev = coverFor;
            prevShader = coverShader;
            coverFor = art;
            coverShader = art == null ? null : new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            coverAt = up;
        }
        float in = fadeIn(up - coverAt, COVER_FADE_MS);
        coverRect.set(left, top, left + side, bottom);
        if (coverFor != null || coverPrev != null) {
            coverShade.setAlpha(0x80);
            c.drawRoundRect(coverRect.left, coverRect.top + 14 * k, coverRect.right, coverRect.bottom + 14 * k, 20 * k, 20 * k, coverShade);
        }
        trackPaint.setColor(0x26FFFFFF);
        c.drawRoundRect(coverRect, 20 * k, 20 * k, trackPaint);     // the frame, under the covers
        if (coverPrev != null && in < 1) drawCover(c, coverPrev, prevShader, 1, side, left, top);
        else coverPrev = null;
        if (coverFor != null) drawCover(c, coverFor, coverShader, in, side, left, top);
        // The texts are shortened once per song, not on every frame
        float maxW = 1100 * k;
        if (!media.title.equals(songFor)) {
            songFor = media.title;
            songText = TextUtils.ellipsize(songFor, new TextPaint(songPaint), maxW, TextUtils.TruncateAt.END).toString();
        }
        String artist = media.artist == null ? "" : media.artist;
        if (!artist.equals(artistFor)) {
            artistFor = artist;
            artistText = TextUtils.ellipsize(artist, new TextPaint(metaPaint), maxW, TextUtils.TruncateAt.END).toString();
        }
        c.drawText(songText, tx, top + 66 * k, songPaint);
        c.drawText(artistText, tx, top + 112 * k, metaPaint);
        boolean live = beat.live();
        double t = up / 1000.0;
        float dt = barsAt == 0 ? 0 : Math.min(0.1f, (up - barsAt) / 1000f);
        barsAt = up;
        float barW = 12 * k, gap = 10 * k, barsBottom = top + 270 * k, tallest = 100 * k;
        float rowW = WIDE_BARS * (barW + gap) - gap;    // the equalizer's width: the progress track takes the same
        for (int i = 0; i < WIDE_BARS; i++) {
            // As on the home screen: the sound per band, or their own dance; paused, they sink to rest
            float level = live ? beat.level(i)
                    : (float) Math.abs(Math.sin(t * HomeView.EQ_SPEED[i % 4] * (0.8f + 0.07f * (i % 5)) + i * 1.3f));
            float target = media.playing ? 0.16f + 0.84f * level : 0.16f;
            if (dt == 0) bars[i] = target;
            else bars[i] += (target - bars[i]) * (1 - (float) Math.exp(-dt * (target > bars[i] ? 22 : 7)));
            float bar = tallest * bars[i];
            float x = tx + i * (barW + gap);
            barRect.set(x, barsBottom - bar, x + barW, barsBottom);
            c.drawRoundRect(barRect, barW / 2, barW / 2, barPaint);
        }
        // Under the bars, the song's progress: a track as wide as them, with the elapsed time and the length below it
        if (media.duration <= 0) return;
        long pos = media.position(), dur = media.duration;
        if (pos / 1000 != elapsedFor) {
            elapsedFor = pos / 1000;
            elapsedText = minutes(pos);
        }
        if (dur / 1000 != durationFor) {
            durationFor = dur / 1000;
            durationText = minutes(dur);
        }
        float mid = bottom - 64 * k, thick = 6 * k, done = rowW * Ui.clamp(pos / (float) dur);
        trackPaint.setColor(0x4DFFFFFF);
        barRect.set(tx, mid - thick / 2, tx + rowW, mid + thick / 2);
        c.drawRoundRect(barRect, thick / 2, thick / 2, trackPaint);
        trackPaint.setColor(0xEBFFFFFF);
        barRect.set(tx, mid - thick / 2, tx + done, mid + thick / 2);
        c.drawRoundRect(barRect, thick / 2, thick / 2, trackPaint);
        c.drawCircle(tx + done, mid, 9 * k, trackPaint);
        float base = bottom - 14 * k;
        c.drawText(elapsedText, tx, base, timePaint);
        c.drawText(durationText, tx + rowW - timePaint.measureText(durationText), base, timePaint);
    }

    /** A cover, `side` square at (left, top), rounded, at `alpha`. */
    private void drawCover(Canvas c, Bitmap art, BitmapShader shader, float alpha, float side, float left, float top) {
        coverMatrix.setScale(side / art.getWidth(), side / art.getHeight());
        coverMatrix.postTranslate(left, top);
        shader.setLocalMatrix(coverMatrix);
        coverPaint.setShader(shader);
        coverPaint.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(coverRect, 20 * k, 20 * k, coverPaint);
        coverPaint.setAlpha(255);
    }

    /** 83000 ms → "1:23". */
    private static String minutes(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private static float fadeIn(long elapsed, long duration) {
        float t = Ui.clamp(elapsed / (float) duration);
        return t * t * (3 - 2 * t);
    }

    /**
     * Black scrims where there is text, stronger where the photo is bright, painted once onto the photo itself (in the
     * background): each frame draws only the photo (the TV's GPU feels every full-screen layer). With the slow zoom,
     * they move a little along with the photo, which makes no difference to the text.
     */
    private static Bitmap withScrims(Bitmap photo, float lightCaption, float lightClock) {
        int w = photo.getWidth(), h = photo.getHeight();
        Canvas c = new Canvas(photo);
        Paint p = new Paint(Paint.DITHER_FLAG);
        float caption = scrimStrength(lightCaption, 0.5f), corner = scrimStrength(lightClock, 0.42f);
        p.setShader(new LinearGradient(0, h, 0, h * 0.42f,
                new int[] {black(0.55f * caption), black(0.33f * caption), black(0.11f * caption), 0},
                new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.42f, w, h, p);
        RadialGradient around = new RadialGradient(0, h, w * 0.7f,
                new int[] {black(caption), black(0.55f * caption), black(0.15f * caption), 0},
                new float[] {0, 0.4f, 0.72f, 1}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1, 0.62f, 0, h);
        around.setLocalMatrix(m);
        p.setShader(around);
        c.drawRect(0, h - w * 0.7f * 0.62f, w * 0.7f, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.3f, new int[] {black(0.3f), black(0.075f), 0},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.3f, p);
        float cx = w * 0.86f, cy = h * 0.15f;
        RadialGradient clockSide = new RadialGradient(cx, cy, w * 0.36f,
                new int[] {black(corner), black(0.6f * corner), black(0.15f * corner), 0},
                new float[] {0, 0.42f, 0.75f, 1}, Shader.TileMode.CLAMP);
        Matrix mc = new Matrix();
        mc.setScale(1, 0.72f, cx, cy);
        clockSide.setLocalMatrix(mc);
        p.setShader(clockSide);
        c.drawRect(w * 0.5f, 0, w, cy + w * 0.36f * 0.72f, p);
        return photo;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }

    private static float scrimStrength(float light, float base) {
        return Math.max(base - 0.1f, Math.min(0.78f, base + (light - 0.3f) * 0.9f));
    }

    // MARK: - Corner: date and weather, glass clock, prices

    private void recordChrome() {
        Canvas c = chrome.beginRecording(w, h);
        try {
            float right = RIGHT * k;
            float x = right;
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                String temp = HomeData.degrees(wx.temp);
                float tw = cornerDate.measureText(temp);
                c.drawText(temp, x - tw, DATE_BASE * k, cornerDate);
                x -= tw + 10 * k;
                String symbol = Symbols.weather(wx.code, wx.isDay);
                float size = 30 * k, sw = Symbols.width(getContext(), symbol, size);
                Symbols.draw(getContext(), c, symbol, x - sw / 2, DATE_BASE * k - Ui.halfCap(cornerDate), size, 0xEBFFFFFF);
                x -= sw + 26 * k;
            }
            c.drawText(date, x - cornerDate.measureText(date), DATE_BASE * k, cornerDate);

            Glass g = glass;
            if (g != null && glassShader != null) {
                // The shadow under the glass: sets the digits apart from a bright photo.
                shadowPaint.setAlpha(140);
                c.drawBitmap(g.shadow, g.x, g.y + 6 * k, shadowPaint);
            } else {
                Rect ink = new Rect();
                cornerClock.getTextBounds(clock, 0, clock.length(), ink);
                c.drawText(clock, right - ink.right, CLOCK_TOP * k - ink.top, cornerClock);
            }

            // Compact prices under the clock, right-aligned.
            float base = (CLOCK_TOP + Ui.cap(cornerClock) / k + QUOTES_GAP) * k;
            float cap = Ui.cap(cornerQuote), gap = 7 * k, spacing = 26 * k;
            int[] coins = HomeData.active();
            int n = HomeData.coinCount();
            String[] values = new String[n];
            float[] glyphs = new float[n];
            float total = spacing * (n - 1);
            for (int i = 0; i < n; i++) {
                values[i] = HomeData.compact(data.prices[coins[i]]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                total += glyphs[i] + gap + cornerQuote.measureText(values[i]);
            }
            float qx = right - total;
            for (int i = 0; i < n; i++) {
                Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], qx + glyphs[i] / 2, base - cap / 2, cap, 0xA6FFFFFF);
                qx += glyphs[i] + gap;
                c.drawText(values[i], qx, base, cornerQuote);
                qx += cornerQuote.measureText(values[i]) + spacing;
            }
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            if (!stale.isEmpty() && n > 0) c.drawText(stale, right - total - 24 * k - cornerStale.measureText(stale), base, cornerStale);
        } finally {
            chrome.endRecording();
        }
        chromeDirty = false;
    }

    private void drawGlass(Canvas c, float alpha, float e) {
        Glass g = glass;
        if (g == null || glassShader == null || alpha <= 0.004f) return;
        // The glass samples the frosted version (1/6 of the photo): same position, with the scale 6 times larger.
        // While music plays it shows the artist's background (drawn to fill the screen) as that fades in.
        float f = Backdrop.PHOTO_FROST;
        boolean music = musicAlpha > 0.004f && musicFrostShader != null;
        float musicScale = music ? (float) w / musicFrost.getWidth() : 0;
        if (current == null) {
            if (!music) return;
            glassShader.setInputShader("imageA", musicFrostShader);
            glassShader.setInputShader("imageB", musicFrostShader);
            glassShader.setFloatUniform("placeA", 0, 0, musicScale);
            glassShader.setFloatUniform("placeB", 0, 0, musicScale);
            glassShader.setFloatUniform("mixB", 0);
        } else if (music) {
            glassShader.setInputShader("imageA", current.shader);
            glassShader.setInputShader("imageB", musicFrostShader);
            glassShader.setFloatUniform("placeA", placeA[0], placeA[1], placeA[2] * f);
            glassShader.setFloatUniform("placeB", 0, 0, musicScale);
            glassShader.setFloatUniform("mixB", musicAlpha);
            boundMusic = true;
        } else {
            if (boundMusic) {
                bindPhotos();   // back to the photos
                boundMusic = false;
            }
            glassShader.setFloatUniform("placeA", placeA[0], placeA[1], placeA[2] * f);
            if (incoming != null) glassShader.setFloatUniform("placeB", placeB[0], placeB[1], placeB[2] * f);
            else glassShader.setFloatUniform("placeB", placeA[0], placeA[1], placeA[2] * f);
            glassShader.setFloatUniform("mixB", incoming == null ? 0 : e);
        }
        glassShader.setFloatUniform("origin", g.x, g.y);
        glassShader.setFloatUniform("ink", g.inkTop, g.inkHeight);
        glassShader.setFloatUniform("refraction", 14 * k * (0.5f + 2 * Prefs.glassAmount()));     // Settings > Liquid Glass
        glassShader.setFloatUniform("specular", 1);
        glassShader.setFloatUniform("alpha", alpha);
        c.drawRect(g.x, g.y, g.x + g.cover.getWidth(), g.y + g.cover.getHeight(), glassPaint);
    }

    /** Builds the glass clock's digits in the background (once a minute) and swaps them in when they're ready. */
    private void buildGlass(String text) {
        glassFor = text;
        if (glassShader == null || text.isEmpty()) {
            chromeDirty = true;
            return;
        }
        final Paint paint = new Paint(cornerClock);
        paint.clearShadowLayer();
        paint.setColor(0xFFFFFFFF);
        final float right = RIGHT * k, top = CLOCK_TOP * k, scale = k;
        final int gen = generation;
        worker.execute(() -> {
            Glass g = makeGlass(text, paint, right, top, scale);
            handler.post(() -> {
                if (gen != generation || !text.equals(glassFor)) return;
                glass = g;
                glassShader.setInputShader("slope", nearest(g.slope));
                glassShader.setInputShader("cover", nearest(g.cover));
                chromeDirty = true;
                invalidate();
            });
        });
    }

    private static BitmapShader nearest(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Frost.filter(s, BitmapShader.FILTER_MODE_NEAREST);
        return s;
    }

    /**
     * The sharp rendering of the digits, the surface height (the blurred rendering: 0.5 at the edge, 1 in the middle of
     * the strokes), its slope (for the lens and the highlight) and the shadow.
     */
    private static Glass makeGlass(String text, Paint paint, float right, float top, float k) {
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        int pad = Math.round(40 * k);
        int w = ink.width() + 2 * pad, h = ink.height() + 2 * pad;
        Bitmap cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(cover).drawText(text, pad - ink.left, pad - ink.top, paint);
        int[] px = new int[w * h];
        cover.getPixels(px, 0, w, 0, 0, w, h);
        int[] alpha = new int[px.length];
        for (int i = 0; i < px.length; i++) alpha[i] = (px[i] >>> 24) * 16;
        int[] height = blur(alpha, w, h, Math.max(2, Math.round(6 * k)), 3);
        int[] soft = blur(alpha, w, h, Math.max(3, Math.round(12 * k)), 3);
        float max = 1;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = height[i + 1] - height[i - 1], gy = height[i + w] - height[i - w];
                max = Math.max(max, (float) Math.sqrt(gx * gx + gy * gy));
            }
        }
        int[] slope = new int[px.length], shadow = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float gx = x > 0 && x < w - 1 ? height[i + 1] - height[i - 1] : 0;
                float gy = y > 0 && y < h - 1 ? height[i + w] - height[i - w] : 0;
                int r = 128 + Math.round(127 * gx / max), g = 128 + Math.round(127 * gy / max);
                int b = Math.min(255, height[i] / 16);
                slope[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                shadow[i] = Math.min(255, soft[i] / 16) << 24;
            }
        }
        int x = Math.round(right - ink.width()) - pad, y = Math.round(top) - pad;
        return new Glass(text, Bitmap.createBitmap(slope, w, h, Bitmap.Config.ARGB_8888), cover,
                Bitmap.createBitmap(shadow, w, h, Bitmap.Config.ARGB_8888), x, y, pad, Math.max(1, ink.height()));
    }

    /** Box blur (passes of radius r in both directions); anything outside the image counts as empty. */
    private static int[] blur(int[] src, int w, int h, int r, int passes) {
        int[] a = src.clone(), b = new int[src.length];
        for (int pass = 0; pass < passes; pass++) {
            box(a, b, w, h, r, true);
            box(b, a, w, h, r, false);
        }
        return a;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep, sum = 0;
            for (int i = 0; i <= r && i < n; i++) sum += in[base + i * step];
            for (int i = 0; i < n; i++) {
                out[base + i * step] = (sum + div / 2) / div;
                int add = i + r + 1, sub = i - r;
                if (add < n) sum += in[base + add * step];
                if (sub >= 0) sum -= in[base + sub * step];
            }
        }
    }

    // MARK: - Photo caption (bottom left)

    private void recordCaption(Slide s) {
        Canvas c = s.caption.beginRecording(w, h);
        try {
            float x = MARGIN * k, metaBase = META_BASE * k, titleBase = metaBase - 58 * k;
            String title = s.pick.title == null ? "" : s.pick.title;
            StaticLayout t = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, Math.round(TITLE_W * k))
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false).build();
            float originY = titleBase - t.getLineBaseline(t.getLineCount() - 1);
            float capTop = originY + t.getLineBaseline(0) - Ui.cap(titlePaint);
            c.drawText(Scenery.isScenery(s.pick) ? Scenery.label() : Recs.periodLabel(), x, capTop - 30 * k, labelPaint);
            c.save();
            c.translate(x - Ui.leftBearing(titlePaint, title), originY);
            t.draw(c);
            c.restore();
            if (s.pick.meta != null && !s.pick.meta.isEmpty()) c.drawText(s.pick.meta, x, metaBase, metaPaint);
        } finally {
            s.caption.endRecording();
        }
        s.caption.setPosition(0, 0, w, h);
    }

    /** The caption fades out fast; the new one rises in once the photo has partly changed (like the home screen). */
    private void drawCaptions(Canvas c, long up) {
        if (incoming == null) {
            float a = current.startedAt == firstAt ? fadeIn(up - firstAt - 700, 900) : 1;
            current.caption.setAlpha(a);
            current.caption.setTranslationY(14 * k * (1 - a));
            current.caption.draw(c);
            return;
        }
        long t = up - incoming.startedAt;
        float out = 1 - Ui.clamp(t / 500f);
        if (out > 0.004f) {
            current.caption.setAlpha(out);
            current.caption.setTranslationY(0);
            current.caption.draw(c);
        }
        float in = fadeIn(t - 1300, 900);
        if (in > 0.004f) {
            incoming.caption.setAlpha(in);
            incoming.caption.setTranslationY(14 * k * (1 - in));
            incoming.caption.draw(c);
        }
    }

    // MARK: - Without photos: the big clock over the aurora

    private void drawFallback(Canvas canvas, long up, float alpha) {
        float t = (up - startedAt) / 1000f;
        if (up - lastSky >= 100) {
            lastSky = up;
            scene.advance(timeOffset + t);
        }
        scene.drawBackground(canvas);
        if (dirty) {
            Canvas rc = content.beginRecording(w, h);
            try {
                drawContent(rc);
            } finally {
                content.endRecording();
            }
            dirty = false;
        }
        double phase = 2 * Math.PI * t / DRIFT_PERIOD_S;
        content.setTranslationX((float) (40 * k * Math.sin(phase)));
        content.setTranslationY((float) (26 * k * Math.sin(2 * phase + 0.7)));
        content.setAlpha(alpha);
        content.draw(canvas);
    }

    private void drawContent(Canvas c) {
        float cx = w / 2f;
        float clockCap = Ui.cap(clockPaint);
        float clockBase = h * 0.5f + clockCap / 2 - 10 * k;
        c.drawText(date, cx, clockBase - clockCap - 34 * k, datePaint);
        c.drawText(clock, cx, clockBase, clockPaint);

        // Weather: symbol, temperature, high and low, centered under the clock.
        HomeData.Weather wx = data.weather;
        if (!Double.isNaN(wx.temp) && Prefs.weather()) {
            float base = clockBase + 76 * k;
            String symbol = Symbols.weather(wx.code, wx.isDay);
            String now = HomeData.degrees(wx.temp);
            String range = Ui.ru ? "Макс. " + HomeData.degrees(wx.max) + "  Мин. " + HomeData.degrees(wx.min)
                    : Ui.en ? "H:" + HomeData.degrees(wx.max) + "  L:" + HomeData.degrees(wx.min)
                    : Ui.de ? "H:" + HomeData.degrees(wx.max) + "  T:" + HomeData.degrees(wx.min)
                    : "Máx. " + HomeData.degrees(wx.max) + "  Mín. " + HomeData.degrees(wx.min);
            float sw = Symbols.width(getContext(), symbol, 32 * k);
            float width = sw + 12 * k + weatherPaint.measureText(now) + 18 * k + weatherDim.measureText(range);
            float x = cx - width / 2;
            Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(weatherPaint), 32 * k, 0xC7FFFFFF);
            x += sw + 12 * k;
            c.drawText(now, x, base, weatherPaint);
            x += weatherPaint.measureText(now) + 18 * k;
            c.drawText(range, x, base, weatherDim);
        }

        // Prices near the bottom edge: small white logo + value, all four on one line.
        float cap = Ui.cap(quotePaint), spacing = 56 * k, gap = 10 * k;
        int[] coins = HomeData.active();
        String[] values = new String[HomeData.coinCount()];
        float total = spacing * (values.length - 1);
        for (int i = 0; i < values.length; i++) {
            values[i] = HomeData.price(data.prices[coins[i]]);
            total += Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap) + gap + quotePaint.measureText(values[i]);
        }
        float x = cx - total / 2, base = h - 92 * k;
        for (int i = 0; i < values.length; i++) {
            float gw = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + gw / 2, base - cap / 2, cap, 0x80FFFFFF);
            x += gw + gap;
            c.drawText(values[i], x, base, quotePaint);
            x += quotePaint.measureText(values[i]) + spacing;
        }
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (!stale.isEmpty() && values.length > 0) c.drawText(Ui.tr("cotações de ", "prices from ") + stale, cx, base + 38 * k, stalePaint);
    }
}
