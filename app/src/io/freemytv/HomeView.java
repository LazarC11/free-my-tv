package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen, in liquid glass:
 *  - the background: the photo of the day in 4K (DailyPhoto; or your own photos), its title above the shelf with ◀ ▶
 *    for another one today. For 5 minutes after you watched something, that show instead (Continue watching, with a
 *    dark fade on the left for its title and Resume). While music plays, the artist's photo (ArtistArt);
 *  - at the left, the side bar: a glass pill with the TV maker's logo, Home, Live TV, the HDMI inputs, Settings
 *    (Settings › Side bar picks them); ← opens it into a panel with their names;
 *  - top right, the clock in clear glass (Glass.clock), the weather beside it and the date below;
 *  - along the bottom, the shelf: square apps on a glass bar, centered; the focused one grows, lights up and a shine
 *    sweeps across it (like the PS5). Apps that don't fit squeeze into dots at either end;
 *  - while music plays, the playing app's tile becomes the album cover, 3× its size, at the left of the shelf, with
 *    the song, its progress and ⏮ ⏯ ⏭ above the shelf;
 *  - ↓ scrolls down to Continue watching (the apps' Watch Next), the weather, then all the apps.
 * Measurements are in a 1920×1080 space (k = width / 1920).
 */
final class HomeView extends View implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener,
        NowPlaying.Listener {
    // Side bar
    private static final float RAIL_X = 40, RAIL_W = 76, RAIL_CY = 460, RAIL_ITEM = 56, RAIL_GAP = 6, RAIL_LOGO = 52;
    private static final float RAIL_OPEN_W = 340, RAIL_ROW = 64, RAIL_HEAD = 34;
    // Clock (top right) and weather
    private static final float CLOCK_RIGHT = 1848, CLOCK_TOP = 30, CLOCK_W = 620, CLOCK_H = 220, CLOCK_BASE = 150;
    // Shelf
    private static final float TILE = 156, GAP = 26, PAD = 26, DOCK_H = 204, DOCK_TOP = 812, DOCK_R = 58, TILE_R = 36, GROW = 0.16f;
    private static final float MUSIC_GAP = 22, MUSIC_PAD = 22, MUSIC_DOCK_LEFT = 632, MUSIC_DOCK_RIGHT = 1840;
    private static final float DOTS = 58;
    // Music
    private static final float COVER = 468, COVER_X = 140, COVER_TOP = 524, TRACK_X = 660;
    // Rows below
    private static final float ROWS_TOP = 1072, CARD_W = 420, CARD_H = 236, CARD_GAP = 24, CARD_R = 28;
    private static final float WEATHER_H = 360, GRID_GAP_Y = 40;
    private static final int GRID_COLS = 8;
    private static final float SCROLL_ROWS = 552;

    private static final long FADE = 900, TOAST_MS = 6000, HOLD_MS = 550, BURST_MS = 1500, HERO_MS = 5 * 60_000L;
    private static final long SHINE_MS = 900, SHINE_EVERY = 4500;
    private static final int Z_RAIL = 0, Z_CAPTION = 1, Z_HERO = 2, Z_MUSIC = 3, Z_SHELF = 4, Z_ROW = 5, Z_GRID = 6;
    private static final int WHITE = 0xFFF5F5F7, INK = 0xFF111114;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final ExecutorService IMAGES = Executors.newSingleThreadExecutor();
    private static final ExecutorService GLASS = Executors.newSingleThreadExecutor();
    private static final Map<String, Bitmap> SQUARES = new LinkedHashMap<>(64, 0.75f, true);

    private final Activity activity;
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final NowPlaying nowPlaying;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final LauncherApps launcherApps;
    private final Spoken spoken;
    private final Glass.Panel glass = new Glass.Panel();
    private final boolean tcl = brandIs("TCL");

    private float k = 1;
    private int w, h;
    private boolean running, framing;
    private long lastFrame, resumedAt;

    // Focus
    private int zone = Z_SHELF, railIndex, shelfIndex, musicIndex = 2, rowIndex, gridIndex;
    private int lastZone = Z_SHELF;

    // Side bar
    private List<HomeApps.Item> rail = new ArrayList<>();
    private Spring[] railFocus = new Spring[0];
    private final Spring railOpen = new Spring(0.32f, 1f, 0);
    private Bitmap logo;

    // Shelf
    private List<HomeApps.Item> apps = new ArrayList<>();       // everything on the shelf (Settings › Shelf)
    private List<HomeApps.Item> shelf = new ArrayList<>();      // what the shelf shows now (not the app that plays)
    private Spring[] tileFocus = new Spring[0], tileSlide = new Spring[0];
    private final Spring shelfScroll = new Spring(0.4f, 1f, 0), dockLeft = new Spring(0.45f, 1f, 0), dockRight = new Spring(0.45f, 1f, 0);
    private int shelfFirst;
    private long shineAt;
    private String[] shelfLabels = new String[0];

    // Background: the photo, the show or the artist, baked once with its shading (one texture to draw)
    private static final class Backing {
        String key = "", title = "", place = "";
        Bitmap image, blurred, behindClock;
    }

    private Backing backing, incoming;
    private long fadeAt;
    private int bakeGeneration;
    private Bitmap clockGlass;
    private String clockFor = "";
    private final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    // Continue watching (the hero) and the rows below
    private List<WatchNext.Entry> watchNext = new ArrayList<>();
    private WatchNext.Entry hero;
    private long heroUntil;
    private final Map<String, Bitmap> cardImages = new HashMap<>();
    private final Map<Bitmap, BitmapShader> cardShaders = new HashMap<>();
    private final Set<String> cardsPending = new HashSet<>();
    private Spring[] cardFocus = new Spring[0];
    private final Spring rowScroll = new Spring(0.4f, 1f, 0), page = new Spring(0.5f, 1f, 0);
    private int rowFirst;

    // All apps
    private List<HomeApps.Item> grid = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels = new String[0];

    // Music
    private NowPlaying.Track track;
    private Bitmap coverArt;
    private BitmapShader coverShader;
    private final Spring music = new Spring(0.5f, 1f, 0);
    private final Spring[] musicFocus = {spring(), spring(), spring(), spring()};
    private String trackTitle = "", trackArtist = "", trackKicker = "";

    // Moving apps on the shelf, holding OK
    private boolean reordering;
    private List<HomeApps.Item> reorderOrigin;
    private final Spring lift = spring();
    private boolean okDown, okHeld, holdArmed, backDown;
    private final Runnable holdStart = this::hold;

    // Clock and weather text
    private String clockText = "", dateText = "", tempText = "", condText = "", weatherSymbol;
    private long clockMinute = -1;
    private int clockGeneration = -1;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Lists
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    // Paints (made at layout)
    private TextPaint datePaint, tempPaint, condPaint, labelPaint, captionPaint, captionBold, chipPaint, railLabel, railSub,
            railHead, kickerPaint, heroTitle, heroMeta, smallPaint, buttonPaint, trackTitlePaint, trackArtistPaint,
            timePaint, sectionPaint, cardTitlePaint, gridLabelPaint, toastPaint, bigTempPaint, hourPaint;
    private Paint clockFont;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG), shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient shineShader;
    private final LinearGradient cardFade = new LinearGradient(0, 0, 0, 1, new int[] {0x00000000, 0x00000000, 0xB3000000},
            new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
    private Bitmap tileShadow;
    private final RectF rect = new RectF(), rect2 = new RectF(), src = new RectF(), oval = new RectF();
    private final Rect dst = new Rect();
    private final Matrix matrix = new Matrix();
    private final Path triangle = new Path();

    private static Spring spring() {
        return new Spring(0.28f, 0.86f, 0);
    }

    private static boolean brandIs(String name) {
        return name.equalsIgnoreCase(Build.BRAND) || name.equalsIgnoreCase(Build.MANUFACTURER);
    }

    // MARK: - Setup and lifecycle

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        nowPlaying = new NowPlaying(a, handler, this);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        spoken = new Spoken(this, new Spoken.Source() {
            @Override public void items(List<Integer> out) { a11yItems(out); }
            @Override public String text(int id) { return a11yText(id); }
            @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
            @Override public int focused() { return a11yId(zone, focusIndex()); }
            @Override public void focus(int id) { a11yFocus(id); }
            @Override public void click(int id) { activate(); }
        });
        setFocusable(true);
        setContentDescription("Free My TV");
        ring.setStyle(Paint.Style.STROKE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        heroUntil = ui.getLong("heroUntil", 0);
        if (tcl) {
            try (InputStream in = a.getAssets().open("brand/tcl.png")) {
                logo = BitmapFactory.decodeStream(in);
            } catch (IOException ignored) {
            }
        }
        // First time: right away (a cold start opens with the apps there); the rest comes from reload().
        setApps(HomeApps.shelf(a, false));
        setRail(HomeApps.rail(a));
        updateClock();
        updateWeather();
    }

    @Override protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        w = width;
        h = height;
        k = width / 1920f;
        glass.k = k;
        makePaints();
        for (HomeApps.Item it : apps) {
            it.square = null;
            it.squareShader = null;
        }
        loadSquares(apps, tilePx());
        if (!grid.isEmpty()) loadSquares(grid, tilePx());
        fitLabels();
        updateTrackText();
        backing = incoming = null;
        clockGlass = null;
        clockFor = "";
        retarget();
        dockLeft.snap(dockLeft.target);
        dockRight.snap(dockRight.target);
        rebake(true);
    }

    private void makePaints() {
        clockFont = Ui.text(Ui.displayHeavy, 152 * k, WHITE);
        clockFont.setLetterSpacing(0.013f);
        datePaint = shadowed(paint(Ui.semibold, 26, 0xE6FFFFFF));
        datePaint.setTextAlign(Paint.Align.RIGHT);
        tempPaint = shadowed(paint(Ui.light, 54, WHITE));
        condPaint = shadowed(paint(Ui.medium, 20, 0xD1FFFFFF));
        labelPaint = shadowed(paint(Ui.semibold, 24, WHITE));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        captionPaint = shadowed(paint(Ui.regular, 22, 0xD9FFFFFF));
        captionBold = shadowed(paint(Ui.semibold, 22, WHITE));
        chipPaint = paint(Ui.medium, 19, WHITE);
        railLabel = paint(Ui.medium, 24, WHITE);
        railSub = paint(Ui.regular, 17, 0x8CFFFFFF);
        railHead = paint(Ui.semibold, 16, 0x80FFFFFF);
        railHead.setLetterSpacing(0.1f);
        kickerPaint = shadowed(paint(Ui.semibold, 18, 0xC7FFFFFF));
        kickerPaint.setLetterSpacing(0.12f);
        heroTitle = shadowed(paint(Ui.displaySemibold, 96, WHITE));
        heroTitle.setLetterSpacing(-0.02f);
        heroMeta = shadowed(paint(Ui.regular, 28, 0xD1FFFFFF));
        smallPaint = shadowed(paint(Ui.regular, 22, 0xBFFFFFFF));
        buttonPaint = paint(Ui.semibold, 26, INK);
        trackTitlePaint = shadowed(paint(Ui.displaySemibold, 60, WHITE));
        trackTitlePaint.setLetterSpacing(-0.015f);
        trackArtistPaint = shadowed(paint(Ui.regular, 26, 0xD9FFFFFF));
        timePaint = shadowed(paint(Ui.regular, 19, 0xCCFFFFFF));
        timePaint.setFontFeatureSettings("'tnum'");
        sectionPaint = shadowed(paint(Ui.semibold, 30, WHITE));
        cardTitlePaint = shadowed(paint(Ui.semibold, 26, WHITE));
        gridLabelPaint = paint(Ui.regular, 19, 0xCCFFFFFF);
        gridLabelPaint.setTextAlign(Paint.Align.CENTER);
        toastPaint = paint(Ui.medium, 26, WHITE);
        bigTempPaint = paint(Ui.light, 96, WHITE);
        hourPaint = paint(Ui.regular, 22, 0xCCFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        shineShader = new LinearGradient(0, 0, 1, 0, new int[] {0x00FFFFFF, 0x14FFFFFF, 0x8CFFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[] {0, 0.3f, 0.5f, 0.7f, 1}, Shader.TileMode.CLAMP);
        shine.setShader(shineShader);
        for (String s : new String[] {"home", "tv", "live_tv", "hdmi", "gamepad", "play", "speaker", "cast", "settings",
                "keystone", "sunrise", "sunset"}) {
            Symbols.warm(getContext(), s, 26 * k);
        }
        makeTileShadow();
    }

    private TextPaint paint(android.graphics.Typeface face, float size, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size * k);
        p.setColor(color);
        return p;
    }

    private TextPaint shadowed(TextPaint p) {
        p.setShadowLayer(14 * k, 0, 2 * k, 0x80000000);
        return p;
    }

    /** The soft shadow under every tile: a blurred rounded square, drawn once (blur is software only). */
    private void makeTileShadow() {
        int blur = Math.max(4, Math.round(22 * k)), size = Math.round(TILE * k);
        int side = size + 4 * blur;
        Bitmap b = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        new Canvas(b).drawRoundRect(2 * blur, 2 * blur, side - 2 * blur, side - 2 * blur, TILE_R * k, TILE_R * k, p);
        tileShadow = b;
        shadowPaint.setColor(0xFF000000);
    }

    void resume() {
        if (running) return;
        running = true;
        long now = SystemClock.uptimeMillis();
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastFrame = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        Photos.importInbox(getContext(), () -> { if (running) rebake(false); });
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        nowPlaying.start();
        onNowPlaying();
        handler.postDelayed(tick, 15_000);
        handler.post(clockTick);
        checkNotices();
        if (!again) reload();
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            if (DailyPhoto.refresh(c)) handler.post(() -> rebake(false));
        });
        Videos.download(c, null);
        page.snap(0);
        zone = Z_SHELF;
        railOpen.snap(0);
        retarget();
        wake();
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        ui.edit().putLong("pausedAt", System.currentTimeMillis()).apply();
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        framing = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(reloadSoon);
        handler.removeCallbacks(progressTick);
        handler.removeCallbacks(shineAgain);
        data.removeListener(this);
        recs.removeListener(this);
        nowPlaying.stop();
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Home pressed on the home screen: back to the start (the first app focused, scrolled to the top). */
    void reset(boolean animated) {
        cancelReorder(!animated);
        zone = Z_SHELF;
        shelfIndex = 0;
        shelfFirst = 0;
        retarget();
        if (!animated) {
            page.snap(0);
            railOpen.snap(0);
            shelfScroll.snap(shelfScroll.target);
        }
        wake();
        spoken.focusMoved();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            if (hero != null && System.currentTimeMillis() > heroUntil) {
                hero = null;
                if (zone == Z_HERO) zone = Z_SHELF;
                rebake(false);
                retarget();
            }
            handler.postDelayed(this, 15_000);
        }
    };

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (updateClock()) {
                makeClockGlass();
                invalidate();
            }
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (!running || track == null) return;
            invalidate();
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable reloadSoon = this::reload;

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(pkg); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(pkg); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(pkg); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { for (String p : pkgs) appsChanged(p); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { for (String p : pkgs) appsChanged(p); }
    };

    private void appsChanged(String pkg) {
        synchronized (SQUARES) {
            SQUARES.keySet().removeIf(key -> key.startsWith(pkg + "@"));
        }
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Data

    @Override public void onData() {
        updateWeather();
        invalidate();
    }

    @Override public void onRecs() {
        if (running && backing != null && (backing.key.startsWith("recs:") || backing.key.equals("colors"))) rebake(false);
    }

    @Override public void onNowPlaying() {
        NowPlaying.Track t = nowPlaying.track();
        boolean had = track != null, has = t != null;
        String oldArtist = track == null ? "" : ArtistArt.mainArtist(track.artist);
        Bitmap oldArt = track == null ? null : track.art;
        track = t;
        music.target = has ? 1 : 0;
        if (has && t.art != coverArt) {
            coverArt = t.art;
            coverShader = t.art == null ? null : new BitmapShader(t.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        updateTrackText();
        handler.removeCallbacks(progressTick);
        if (has && running) handler.postDelayed(progressTick, 1000);
        if (had != has) {
            if (!has && zone == Z_MUSIC) zone = Z_SHELF;
            applyShelf();
        }
        if (had != has || has && (!ArtistArt.mainArtist(t.artist).equals(oldArtist) || t.art != oldArt)) rebake(false);
        retarget();
    }

    private void updateTrackText() {
        if (trackTitlePaint == null || track == null) return;
        float width = (MUSIC_DOCK_RIGHT - TRACK_X - 40) * k;
        trackTitle = fit(track.title, trackTitlePaint, width);
        trackArtist = fit(track.artist, trackArtistPaint, width);
        trackKicker = (track.app.isEmpty() ? Ui.tr("Now playing") : Ui.trf("Now playing on {app}", "app", track.app))
                .toUpperCase(Ui.locale());
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute && Ui.generation == clockGeneration) return false;
        clockMinute = minute;
        clockGeneration = Ui.generation;
        clockText = Ui.clock(now);
        String d = Ui.date(now);
        dateText = d.isEmpty() ? d : d.substring(0, 1).toUpperCase(Ui.locale()) + d.substring(1);
        return true;
    }

    private void updateWeather() {
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) {
            tempText = condText = "";
            weatherSymbol = null;
            return;
        }
        weatherSymbol = Symbols.weather(wx.code, wx.isDay);
        tempText = HomeData.degrees(wx.temp);
        condText = HomeData.condition(wx.code, wx.isDay);
    }

    // MARK: - Lists (in the background, so coming home is never held up)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        long up = SystemClock.uptimeMillis();
        final boolean fresh = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration || up - appsAt > 10 * 60_000L;
        if (fresh) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        final long pausedAt = ui.getLong("pausedAt", 0);
        final boolean needGrid = fresh || grid.isEmpty();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = fresh ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> r = HomeApps.rail(c);
            final List<WatchNext.Entry> next = WatchNext.load(c);
            final List<HomeApps.Item> all = needGrid ? HomeApps.allApps(c) : null;
            handler.post(() -> {
                if (s != null && !reordering) setApps(s);
                setRail(r);
                setWatchNext(next, pausedAt);
                if (all != null) setGrid(all);
            });
        });
    }

    private void setApps(List<HomeApps.Item> list) {
        Map<String, HomeApps.Item> had = new HashMap<>();
        for (HomeApps.Item it : apps) if (it.square != null) had.put(squareKey(it, tilePx()), it);
        for (HomeApps.Item it : list) {
            HomeApps.Item old = had.get(squareKey(it, tilePx()));
            if (old != null) {
                it.square = old.square;
                it.squareShader = old.squareShader;
            }
        }
        apps = list;
        loadSquares(list, tilePx());
        applyShelf();
    }

    /** What the shelf shows: the apps, without the one that plays music (it is the big cover then). */
    private void applyShelf() {
        List<HomeApps.Item> list = new ArrayList<>();
        String playing = track != null ? track.pkg : null;
        for (HomeApps.Item it : apps) if (playing == null || !playing.equals(it.pkg)) list.add(it);
        String focusedKey = shelfIndex < shelf.size() ? shelf.get(shelfIndex).key : null;
        Spring[] focus = new Spring[list.size()], slide = new Spring[list.size()];
        for (int i = 0; i < list.size(); i++) {
            focus[i] = i < tileFocus.length ? tileFocus[i] : spring();
            slide[i] = i < tileSlide.length ? tileSlide[i] : new Spring(0.32f, 0.9f, 0);
        }
        shelf = list;
        tileFocus = focus;
        tileSlide = slide;
        shelfIndex = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).key.equals(focusedKey)) shelfIndex = i;
        fitLabels();
        retarget();
        invalidate();
    }

    private void setRail(List<HomeApps.Item> list) {
        String focusedKey = railIndex < rail.size() ? rail.get(railIndex).key : null;
        Spring[] focus = new Spring[list.size()];
        for (int i = 0; i < list.size(); i++) focus[i] = i < railFocus.length ? railFocus[i] : spring();
        rail = list;
        railFocus = focus;
        railIndex = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).key.equals(focusedKey)) railIndex = i;
        retarget();
        invalidate();
    }

    /**
     * Continue watching: the apps' Watch Next list. Something watched while we were away (up to 5 minutes ago) takes
     * the background for 5 minutes; after that it waits in the row below the shelf.
     */
    private void setWatchNext(List<WatchNext.Entry> next, long pausedAt) {
        watchNext = next;
        Spring[] focus = new Spring[next.size()];
        for (int i = 0; i < next.size(); i++) focus[i] = i < cardFocus.length ? cardFocus[i] : spring();
        cardFocus = focus;
        rowIndex = Math.min(rowIndex, Math.max(0, next.size() - 1));
        long now = System.currentTimeMillis();
        WatchNext.Entry newest = next.isEmpty() ? null : next.get(0);
        if (newest != null && pausedAt > 0 && newest.engaged > pausedAt - 60_000 && now - newest.engaged < HERO_MS) {
            String key = heroKey(newest);
            if (!key.equals(ui.getString("heroKey", ""))) {
                heroUntil = now + HERO_MS;
                ui.edit().putLong("heroUntil", heroUntil).putString("heroKey", key).apply();
            }
        }
        WatchNext.Entry was = hero;
        hero = null;
        if (now < heroUntil) {
            for (WatchNext.Entry e : next) if (heroKey(e).equals(ui.getString("heroKey", ""))) hero = e;
        }
        if (hero == null && zone == Z_HERO) zone = Z_SHELF;
        if ((was == null) != (hero == null) || (hero != null && !heroKey(hero).equals(heroKey(was)))) rebake(false);
        if (zone == Z_ROW && next.isEmpty()) zone = Z_SHELF;
        loadCards();
        retarget();
        invalidate();
    }

    private static String heroKey(WatchNext.Entry e) {
        return e == null ? "" : e.pkg + "|" + e.title + "|" + e.subtitle;
    }

    private void setGrid(List<HomeApps.Item> list) {
        Spring[] focus = new Spring[list.size()];
        for (int i = 0; i < list.size(); i++) focus[i] = i < gridFocus.length ? gridFocus[i] : spring();
        grid = list;
        gridFocus = focus;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        fitLabels();
        loadSquares(list, tilePx());
        retarget();
    }

    private void fitLabels() {
        shelfLabels = new String[shelf.size()];
        for (int i = 0; i < shelf.size(); i++) {
            String label = shelf.get(i).label;
            shelfLabels[i] = labelPaint == null ? label : fit(label, labelPaint, 320 * k);
        }
        gridLabels = new String[grid.size()];
        for (int i = 0; i < grid.size(); i++) {
            String label = grid.get(i).label;
            gridLabels[i] = gridLabelPaint == null ? label : fit(label, gridLabelPaint, (TILE + GAP - 6) * k);
        }
    }

    /** The cards' images, one at a time in the background. */
    private void loadCards() {
        if (w == 0) return;
        final int cw = Math.round(CARD_W * k * 1.06f), ch = Math.round(CARD_H * k * 1.06f);
        final Context c = getContext().getApplicationContext();
        for (WatchNext.Entry e : watchNext) {
            final String uri = e.image;
            if (uri == null || uri.isEmpty() || cardImages.containsKey(uri) || !cardsPending.add(uri)) continue;
            IMAGES.execute(() -> {
                Bitmap b = WatchNext.image(c, uri, cw, ch);
                BitmapShader s = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                handler.post(() -> {
                    cardsPending.remove(uri);
                    if (b == null) return;
                    if (cardImages.size() > 30) {
                        cardImages.clear();
                        cardShaders.clear();
                    }
                    cardImages.put(uri, b);
                    cardShaders.put(b, s);
                    invalidate();
                });
            });
        }
    }

    // MARK: - Square tiles

    private int tilePx() {
        return Math.max(1, Math.round(TILE * k * (1 + GROW)));
    }

    private static String squareKey(HomeApps.Item it, int px) {
        return (it.pkg != null ? it.pkg : it.key) + "@" + px;
    }

    private void loadSquares(List<HomeApps.Item> items, final int px) {
        if (w == 0) return;
        final Context c = getContext().getApplicationContext();
        final List<HomeApps.Item> todo = new ArrayList<>();
        for (HomeApps.Item it : items) {
            if (it.square != null) continue;
            Bitmap cached;
            synchronized (SQUARES) {
                cached = SQUARES.get(squareKey(it, px));
            }
            if (cached != null) setSquare(it, cached); else todo.add(it);
        }
        if (todo.isEmpty()) return;
        LOADER.execute(() -> {
            for (HomeApps.Item it : todo) {
                Bitmap b;
                try {
                    b = HomeApps.squareTile(c, it, px);
                } catch (RuntimeException | OutOfMemoryError e) {
                    AppLog.w("tile " + it.label + ": " + e.getMessage());
                    continue;
                }
                b.prepareToDraw();
                synchronized (SQUARES) {
                    SQUARES.put(squareKey(it, px), b);
                    while (SQUARES.size() > 140) SQUARES.remove(SQUARES.keySet().iterator().next());
                }
                handler.post(() -> {
                    setSquare(it, b);
                    invalidate();
                });
            }
        });
    }

    private static void setSquare(HomeApps.Item it, Bitmap b) {
        if (it.square == b && it.squareShader != null) return;
        it.square = b;
        it.squareShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    // MARK: - Background

    /** What the background shows now: the artist (music), the show (Continue watching) or the photo of the day. */
    private void rebake(boolean first) {
        if (w == 0) return;
        final Context c = getContext().getApplicationContext();
        final int gen = ++bakeGeneration;
        // At the screen's own size: 4K photos stay 4K on a TV that draws its UI in 4K.
        final int bw = w, bh = h;
        final NowPlaying.Track t = track;
        final WatchNext.Entry e = hero;
        final boolean ownPhotos = "mine".equals(Prefs.background()), artistPhoto = !"album".equals(Prefs.musicBackground());
        final float kk = bw / 1920f;
        IMAGES.execute(() -> {
            Backing b = null;
            try {
                if (t != null) b = bakeMusic(c, t, artistPhoto, bw, bh);
                if (b == null && e != null) b = bakeHero(c, e, bw, bh, kk);
                if (b == null) b = bakePhoto(c, ownPhotos, bw, bh);
            } catch (RuntimeException | OutOfMemoryError ex) {
                AppLog.w("background: " + ex.getMessage());
            }
            final Backing ready = b;
            handler.post(() -> {
                if (gen != bakeGeneration || ready == null) return;
                Backing now = incoming != null ? incoming : backing;
                if (now != null && ready.key.equals(now.key) && !first) return;
                if (backing == null || first) {
                    backing = ready;
                    incoming = null;
                } else {
                    if (incoming != null) backing = incoming;
                    incoming = ready;
                    fadeAt = SystemClock.uptimeMillis();
                }
                makeClockGlass();
                wake();
            });
        });
    }

    private Backing bakePhoto(Context c, boolean own, int bw, int bh) {
        Backing b = new Backing();
        File f = null;
        if (own) {
            List<File> mine = Photos.files(c);
            if (!mine.isEmpty()) {
                f = mine.get((int) Math.floorMod(System.currentTimeMillis() / 86_400_000L, (long) mine.size()));
                b.key = "mine:" + f.getName();
            }
        }
        if (f == null) {
            DailyPhoto.Photo p = DailyPhoto.current(c);
            if (p != null) {
                f = p.file;
                b.key = "daily:" + f.getName();
                b.title = p.title;
                b.place = p.place;
            }
        }
        if (f == null) {
            for (Recs.Pick p : recs.showcase()) {
                File ready = recs.readyImage(p);
                if (ready != null) {
                    f = ready;
                    b.key = "recs:" + ready.getName();
                    break;
                }
            }
        }
        Bitmap soft = f == null ? null : decode(f, bw, bh);
        if (soft == null) {
            soft = wallpaper(bw, bh);
            b.key = "colors";
            b.title = b.place = "";
        }
        Canvas cv = new Canvas(soft);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, bh, new int[] {0x59000000, 0x00000000, 0x00000000, 0x8C000000},
                new float[] {0, 0.22f, 0.55f, 1}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, bw, bh, p);
        return finish(b, soft);
    }

    /** The show on the right, fading into black on the left, where its title and Resume sit. */
    private Backing bakeHero(Context c, WatchNext.Entry e, int bw, int bh, float kk) {
        int left = Math.round(420 * kk), aw = bw - left;
        Bitmap art = WatchNext.image(c, e.image, aw, bh);
        if (art == null) return null;
        Bitmap soft = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(soft);
        cv.drawColor(0xFF000000);
        cv.drawBitmap(art, left, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        art.recycle();
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, bw, 0, new int[] {0xFF000000, 0xFF000000, 0xD9000000, 0x59000000, 0x00000000},
                new float[] {0, 0.2f, 0.32f, 0.52f, 0.7f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, bw, bh, p);
        p.setShader(new LinearGradient(0, 0, 0, bh, new int[] {0x40000000, 0x00000000, 0x00000000, 0xCC000000},
                new float[] {0, 0.2f, 0.55f, 1}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, bw, bh, p);
        Backing b = new Backing();
        b.key = "hero:" + heroKey(e);
        return finish(b, soft);
    }

    /** The artist's photo (or the album cover, blurred), darker on the left and at the bottom for the text. */
    private Backing bakeMusic(Context c, NowPlaying.Track t, boolean artistPhoto, int bw, int bh) {
        Backing b = new Backing();
        Bitmap soft = null;
        if (artistPhoto) {
            File f = ArtistArt.photo(c, t.artist);
            if (f != null) {
                soft = decode(f, bw, bh);
                b.key = "artist:" + f.getName();
            }
        }
        Bitmap art = t.art;
        if (soft == null && art != null && !art.isRecycled()) {
            Bitmap small = Bitmap.createScaledBitmap(art, 48, Math.max(1, 48 * bh / bw), true);
            Bitmap big = Bitmap.createScaledBitmap(small, bw, bh, true);
            soft = big.isMutable() ? big : big.copy(Bitmap.Config.ARGB_8888, true);
            new Canvas(soft).drawColor(0x66000000);
            b.key = "album:" + System.identityHashCode(art);
        }
        if (soft == null) return null;
        Canvas cv = new Canvas(soft);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, bw, 0, new int[] {0xBF000000, 0x73000000, 0x0D000000, 0x00000000},
                new float[] {0, 0.3f, 0.6f, 1}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, bw, bh, p);
        p.setShader(new LinearGradient(0, 0, 0, bh, new int[] {0x4D000000, 0x00000000, 0x40000000, 0xD9000000},
                new float[] {0, 0.25f, 0.55f, 1}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, bw, bh, p);
        return finish(b, soft);
    }

    /** Keeps what is under the clock (for its glass) and a blurred copy (for scrolling), then moves it to the GPU. */
    private Backing finish(Backing b, Bitmap soft) {
        int bw = soft.getWidth(), bh = soft.getHeight();
        float kk = bw / 1920f;
        int cx = Math.round((CLOCK_RIGHT - CLOCK_W) * kk), cy = Math.round(CLOCK_TOP * kk);
        int cw = Math.min(bw - cx, Math.round(CLOCK_W * kk)), ch = Math.min(bh - cy, Math.round(CLOCK_H * kk));
        b.behindClock = Bitmap.createBitmap(soft, cx, cy, cw, ch);
        Bitmap small = Bitmap.createScaledBitmap(soft, Math.max(1, bw / 16), Math.max(1, bh / 16), true);
        b.blurred = Bitmap.createScaledBitmap(small, Math.max(1, bw / 4), Math.max(1, bh / 4), true);
        small.recycle();
        b.image = gpu(soft);
        return b;
    }

    /** The clock's glass for this minute and this background, made in the background (it is slow to make). */
    private void makeClockGlass() {
        final Backing b = incoming != null ? incoming : backing;
        if (b == null || b.behindClock == null || clockFont == null || clockText.isEmpty()) return;
        final String key = b.key + "|" + clockText + "|" + w;
        if (key.equals(clockFor)) return;
        clockFor = key;
        final String text = clockText;
        final Paint font = new Paint(clockFont);
        final float scale = b.behindClock.getWidth() / (CLOCK_W * k);
        font.setTextSize(clockFont.getTextSize() * scale);
        final float right = (CLOCK_W - 6) * k * scale, base = CLOCK_BASE * k * scale, kk = k * scale;
        GLASS.execute(() -> {
            Bitmap g;
            try {
                g = Glass.clock(b.behindClock, text, font, right, base, kk);
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("glass clock: " + e.getMessage());
                return;
            }
            handler.post(() -> {
                if (!key.equals(clockFor)) return;
                clockGlass = g;
                invalidate();
            });
        });
    }

    private static Bitmap decode(File f, int w, int h) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
            opts.inMutable = true;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) return null;
            b = WatchNext.crop(b, w, h);
            if (!b.isMutable()) {
                Bitmap m = b.copy(Bitmap.Config.ARGB_8888, true);
                b.recycle();
                b = m;
            }
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("background: " + e.getMessage());
            return null;
        }
    }

    /** A photo cropped to w × h (the screensaver's photos use it too). */
    static Bitmap decodeFile(File f, int w, int h, boolean unused) {
        return decode(f, w, h);
    }

    private static Bitmap gpu(Bitmap b) {
        if (Build.VERSION.SDK_INT < 26) return b;
        try {
            Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
            if (hw != null) {
                b.recycle();
                return hw;
            }
        } catch (RuntimeException ignored) {
        }
        return b;
    }

    /** No photo yet (first start, offline): deep colors. */
    private static Bitmap wallpaper(int w, int h) {
        int sw = 384, sh = Math.max(1, Math.round(384f * h / w));
        Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        c.drawColor(0xFF0A1222);
        Paint p = new Paint(Paint.DITHER_FLAG);
        int[] colors = {0xFF1F4F86, 0xFF18707A, 0xFF3B2C70};
        float[][] blobs = {{0.78f, 0.18f, 0.62f}, {0.12f, 0.92f, 0.7f}, {0.55f, 0.75f, 0.5f}};
        for (int i = 0; i < 3; i++) {
            float[] b = blobs[i];
            p.setShader(new RadialGradient(b[0] * sw, b[1] * sh, b[2] * sw, Ui.alpha(colors[i], 0.85f), Ui.alpha(colors[i], 0), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, sw, sh, p);
        }
        Bitmap big = Bitmap.createScaledBitmap(small, w, h, true);
        small.recycle();
        return big.isMutable() ? big : big.copy(Bitmap.Config.ARGB_8888, true);
    }

    // MARK: - Animation

    private void wake() {
        if (!running || framing) {
            invalidate();
            return;
        }
        framing = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long nanos) {
        if (!running) {
            framing = false;
            return;
        }
        float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.05f, (nanos - lastFrame) / 1e9f);
        lastFrame = nanos;
        boolean moving = step(dt);
        long up = SystemClock.uptimeMillis();
        if (incoming != null && up - fadeAt >= FADE) {
            backing = incoming;
            incoming = null;
        }
        moving |= incoming != null;
        moving |= tickToast(up);
        moving |= shining(up);
        invalidate();
        if (moving) Choreographer.getInstance().postFrameCallback(this);
        else framing = false;
    }

    /** The shine sweeps for SHINE_MS after the focus lands on an app, then again every SHINE_EVERY. */
    private boolean shining(long up) {
        if (zone != Z_SHELF || shelf.isEmpty() || reordering) return false;
        long t = (up - shineAt) % SHINE_EVERY;
        if (t < SHINE_MS) return true;
        handler.removeCallbacks(shineAgain);
        handler.postDelayed(shineAgain, SHINE_EVERY - t);
        return false;
    }

    private final Runnable shineAgain = this::wake;

    private boolean step(float dt) {
        boolean m = false;
        for (Spring s : railFocus) m |= s.step(dt);
        for (Spring s : tileFocus) m |= s.step(dt);
        for (Spring s : tileSlide) m |= s.step(dt);
        for (Spring s : cardFocus) m |= s.step(dt);
        for (Spring s : gridFocus) m |= s.step(dt);
        for (Spring s : musicFocus) m |= s.step(dt);
        m |= railOpen.step(dt);
        m |= shelfScroll.step(dt);
        m |= dockLeft.step(dt);
        m |= dockRight.step(dt);
        m |= rowScroll.step(dt);
        m |= page.step(dt);
        m |= music.step(dt);
        m |= lift.step(dt);
        return m;
    }

    /** Points every focus spring and scroll at the current focus. */
    private void retarget() {
        for (int i = 0; i < railFocus.length; i++) railFocus[i].target = zone == Z_RAIL && railIndex == i ? 1 : 0;
        for (int i = 0; i < tileFocus.length; i++) tileFocus[i].target = zone == Z_SHELF && shelfIndex == i ? 1 : 0;
        for (int i = 0; i < cardFocus.length; i++) cardFocus[i].target = zone == Z_ROW && rowIndex == i ? 1 : 0;
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i].target = zone == Z_GRID && gridIndex == i ? 1 : 0;
        for (int i = 0; i < musicFocus.length; i++) musicFocus[i].target = zone == Z_MUSIC && musicIndex == i ? 1 : 0;
        railOpen.target = zone == Z_RAIL ? 1 : 0;
        // The shelf: where the bar is, how many apps fit, and the window of apps shown.
        float left, right;
        if (track != null) {
            left = MUSIC_DOCK_LEFT * k;
            right = MUSIC_DOCK_RIGHT * k;
        } else {
            int fit = Math.max(1, (int) Math.floor((1640 - 2 * PAD + GAP) / (TILE + GAP)));
            int n = Math.max(1, Math.min(shelf.size(), fit));
            float dw = (n * TILE + (n - 1) * GAP + 2 * PAD) * k;
            left = (w - dw) / 2f;
            right = left + dw;
        }
        dockLeft.target = left;
        dockRight.target = right;
        int slots = slots();
        if (shelfIndex < shelfFirst) shelfFirst = shelfIndex;
        if (shelfIndex > shelfFirst + slots - 1) shelfFirst = shelfIndex - slots + 1;
        shelfFirst = Math.max(0, Math.min(shelfFirst, Math.max(0, shelf.size() - slots)));
        shelfScroll.target = shelfFirst;
        // The rows below.
        int visibleCards = 4;
        if (rowIndex < rowFirst) rowFirst = rowIndex;
        if (rowIndex > rowFirst + visibleCards - 1) rowFirst = rowIndex - visibleCards + 1;
        rowScroll.target = rowFirst * (CARD_W + CARD_GAP) * k;
        if (zone == Z_ROW) page.target = SCROLL_ROWS * k;
        else if (zone == Z_GRID) page.target = Math.max(SCROLL_ROWS * k, gridRowTop(gridIndex / GRID_COLS) - 660 * k);
        else page.target = 0;
        lift.target = reordering ? 1 : 0;
        wake();
    }

    /** How many apps the shelf shows at once (with room for the dots when some don't fit). */
    private int slots() {
        float gap = track != null ? MUSIC_GAP : GAP, pad = track != null ? MUSIC_PAD : PAD;
        float width = (dockRight.target - dockLeft.target) / Math.max(1e-3f, k);
        int n = Math.max(1, (int) Math.floor((width - 2 * pad + gap) / (TILE + gap)));
        if (shelf.size() <= n) return n;
        return Math.max(1, (int) Math.floor((width - 2 * pad - 2 * DOTS + gap) / (TILE + gap)));
    }

    // MARK: - Layout of the rows below (page coordinates)

    private float rowTop() {
        return ROWS_TOP * k;
    }

    private float weatherTop() {
        return rowTop() + (watchNext.isEmpty() ? 0 : (56 + CARD_H + 64) * k);
    }

    private boolean showWeather() {
        return Prefs.weather() && !Double.isNaN(data.weather.temp);
    }

    private float gridTop() {
        return weatherTop() + (showWeather() ? (56 + WEATHER_H + GRID_GAP_Y + 20) * k : 0) + 56 * k;
    }

    private float gridRowTop(int row) {
        return gridTop() + row * (TILE + 56) * k;
    }

    private float contentLeft() {
        return 245 * k;
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (w == 0 || clockFont == null) return;
        float scroll = page.value, gone = Ui.clamp(scroll / (500 * k));
        drawBackground(c, gone);
        int save = c.save();
        c.translate(0, -scroll);
        float m = Ui.clamp(music.value);
        if (gone < 1) {
            if (track != null) drawMusic(c, 1 - gone);
            else if (hero != null) drawHero(c, 1 - gone);
            else drawCaption(c, 1 - gone);
        }
        drawShelf(c, m);
        if (scroll > 1) drawRows(c, scroll);
        c.restoreToCount(save);
        drawClock(c);
        drawRail(c);
        drawToast(c);
    }

    private void drawBackground(Canvas c, float gone) {
        dst.set(0, 0, w, h);
        if (backing == null) {
            c.drawColor(0xFF0B0C10);
        } else {
            photo.setAlpha(255);
            c.drawBitmap(backing.image, null, dst, photo);
        }
        if (incoming != null) {
            photo.setAlpha(Math.round(255 * fadeT()));
            c.drawBitmap(incoming.image, null, dst, photo);
            photo.setAlpha(255);
        }
        if (gone > 0) {
            Backing b = incoming != null ? incoming : backing;
            if (b != null && b.blurred != null) {
                photo.setAlpha(Math.round(255 * gone));
                c.drawBitmap(b.blurred, null, dst, photo);
                photo.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, 0.5f * gone));
            c.drawRect(0, 0, w, h, fill);
        }
    }

    private float fadeT() {
        float t = Ui.clamp((SystemClock.uptimeMillis() - fadeAt) / (float) FADE);
        return t * t * (3 - 2 * t);
    }

    private Bitmap bg() {
        return backing == null ? null : backing.image;
    }

    private Bitmap bg2() {
        return incoming == null ? null : incoming.image;
    }

    /** Top right: the glass clock, the weather beside it, the date below. */
    private void drawClock(Canvas c) {
        float left = (CLOCK_RIGHT - CLOCK_W) * k, top = CLOCK_TOP * k;
        if (clockGlass != null) {
            rect.set(left, top, left + CLOCK_W * k, top + CLOCK_H * k);
            photo.setAlpha(255);
            c.drawBitmap(clockGlass, null, rect, photo);
        }
        c.drawText(dateText, CLOCK_RIGHT * k - 6 * k, 222 * k, datePaint);
        if (!tempText.isEmpty()) {
            float right = 1520 * k, cy = 122 * k;
            fill.setShader(null);
            fill.setColor(0x4DFFFFFF);
            c.drawRect(right + 25 * k, 80 * k, right + 27 * k, 176 * k, fill);
            float tw = Math.max(tempPaint.measureText(tempText), condPaint.measureText(condText)), tx = right - tw;
            c.drawText(tempText, tx, cy + 6 * k, tempPaint);
            c.drawText(condText, tx, cy + 40 * k, condPaint);
            if (weatherSymbol != null) Symbols.draw(getContext(), c, weatherSymbol, tx - 44 * k, cy, 50 * k, WHITE);
        }
    }

    /** The side bar: a glass pill with icons; open (←), a glass panel with their names. */
    private void drawRail(Canvas c) {
        if (rail.isEmpty()) return;
        float f = Ui.clamp(railOpen.value), o = railOpen.value;
        float width = (RAIL_W + (RAIL_OPEN_W - RAIL_W) * o) * k;
        float rowH = (RAIL_ITEM + RAIL_GAP + (RAIL_ROW + 4 - RAIL_ITEM - RAIL_GAP) * o) * k;
        boolean hasLogo = logo != null;
        boolean anyInput = false;
        for (HomeApps.Item it : rail) anyInput |= it.kind == HomeApps.INPUT || it.kind == HomeApps.LIVE_TV;
        float head = anyInput ? RAIL_HEAD * k * f : 0;
        float height = 20 * k + (hasLogo ? (RAIL_LOGO + 14) * k : 0) + rail.size() * rowH + 14 * k + head;
        float top = RAIL_CY * k - height / 2, left = RAIL_X * k;
        rect.set(left, top, left + width, top + height);
        float radius = (38 - 4 * o) * k;
        glass.draw(c, rect, radius, bg(), bg2(), incoming == null ? 0 : fadeT(), w, h, 1);
        if (o > 0.02f) {
            // Open: a darker glass, so the names read over any photo.
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, 0.35f * f));
            c.drawRoundRect(rect, radius, radius, fill);
        }
        float y = top + 10 * k;
        if (hasLogo) {
            float lw = (50 + 42 * f) * k, lh = lw * logo.getHeight() / logo.getWidth();
            float lx = left + (RAIL_W * k - lw) / 2 * (1 - f) + 28 * k * f;
            rect2.set(lx, y + (RAIL_LOGO * k - lh) / 2, lx + lw, y + (RAIL_LOGO * k + lh) / 2);
            image.setShader(null);
            image.setAlpha(242);
            c.drawBitmap(logo, null, rect2, image);
            y += RAIL_LOGO * k;
            separator(c, left, width, y + 7 * k, f);
            y += 14 * k;
        }
        boolean headDone = false;
        for (int i = 0; i < rail.size(); i++) {
            HomeApps.Item it = rail.get(i);
            if (i == rail.size() - 1) {
                separator(c, left, width, y + 7 * k, f);
                y += 14 * k;
            }
            if (!headDone && head > 0 && (it.kind == HomeApps.INPUT || it.kind == HomeApps.LIVE_TV)) {
                railHead.setAlpha(Math.round(128 * f));
                c.drawText(Ui.tr("Inputs").toUpperCase(Ui.locale()), left + 28 * k, y + 24 * k, railHead);
                y += head;
                headDone = true;
            }
            float fv = Ui.clamp(railFocus[i].value);
            float itemH = rowH - (RAIL_GAP + 4 * o) * k;
            float ix = left + (RAIL_W - RAIL_ITEM) / 2 * k * (1 - f) + 12 * k * f;
            float iw = RAIL_ITEM * k + (width - 24 * k - RAIL_ITEM * k) * f;
            rect2.set(ix, y, ix + iw, y + itemH);
            if (fv > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xF2FFFFFF, fv));
                float rr = Math.min(itemH / 2, (28 - 6 * f) * k);
                c.drawRoundRect(rect2, rr, rr, fill);
            }
            int ink = blend(it.connected || it.kind != HomeApps.INPUT ? WHITE : 0x73FFFFFF, INK, fv);
            float iconCx = ix + (iw / 2) * (1 - f) + (30 * k) * f;
            float cy = rect2.centerY();
            if ("wifi".equals(it.key)) drawWifi(c, iconCx, cy + 4 * k, 12 * k, ink);
            else Symbols.draw(getContext(), c, it.symbol != null ? it.symbol : "hdmi", iconCx, cy, 26 * k, ink);
            if (it.kind == HomeApps.INPUT && it.connected) {
                fill.setShader(null);
                fill.setColor(0xFF30D158);
                float dx = f > 0.5f ? rect2.right - 24 * k : iconCx + 16 * k, dy = f > 0.5f ? cy : cy - 15 * k;
                c.drawCircle(dx, dy, 4.5f * k, fill);
            }
            if (f > 0.02f) {
                int a = Math.round(255 * f);
                railLabel.setColor(blend(WHITE, INK, fv));
                railLabel.setAlpha(a);
                float tx = ix + 62 * k;
                if (it.sub != null && !it.sub.isEmpty()) {
                    c.drawText(it.label, tx, cy - 2 * k, railLabel);
                    railSub.setColor(blend(0x8CFFFFFF, 0x80000000, fv));
                    railSub.setAlpha(Math.round(railSub.getAlpha() * f));
                    c.drawText(it.sub, tx, cy + 21 * k, railSub);
                } else {
                    c.drawText(it.label, tx, cy + Ui.halfCap(railLabel), railLabel);
                }
            }
            y += rowH;
        }
    }

    private void separator(Canvas c, float left, float width, float y, float f) {
        fill.setShader(null);
        fill.setColor(0x40FFFFFF);
        float inset = 24 * k + 4 * k * f;
        c.drawRect(left + inset, y - 0.75f * k, left + width - inset, y + 0.75f * k, fill);
    }

    private void drawWifi(Canvas c, float cx, float cy, float r, int color) {
        arc.setStrokeWidth(r * 0.24f);
        arc.setColor(color);
        for (int i = 1; i <= 3; i++) {
            float rr = r * (0.42f + 0.38f * (i - 1)) * 1.15f;
            oval.set(cx - rr, cy - rr, cx + rr, cy + rr);
            c.drawArc(oval, 225, 90, false, arc);
        }
        fill.setShader(null);
        fill.setColor(color);
        c.drawCircle(cx, cy - r * 0.05f, r * 0.16f, fill);
    }

    /** Above the shelf: the photo's title and place, and ◀ ▶ for another photo today. */
    private void drawCaption(Canvas c, float alpha) {
        Backing b = incoming != null ? incoming : backing;
        if (b == null || b.title.isEmpty()) return;
        float x = dockLeft.value + PAD * k, base = 774 * k;
        String title = b.title, place = b.place.isEmpty() || b.place.equals(b.title) ? "" : "  ·  " + b.place;
        captionBold.setAlpha(Math.round(255 * alpha));
        c.drawText(title, x, base, captionBold);
        float tx = x + captionBold.measureText(title);
        captionPaint.setAlpha(Math.round(217 * alpha));
        String fitPlace = fit(place, captionPaint, 860 * k - (tx - x));
        c.drawText(fitPlace, tx, base, captionPaint);
        tx += captionPaint.measureText(fitPlace) + 16 * k;
        boolean focused = zone == Z_CAPTION;
        String chip = "◀ ▶  " + Ui.tr("Another photo today");
        float cw = chipPaint.measureText(chip) + 30 * k;
        rect.set(tx, base - 27 * k, tx + cw, base + 9 * k);
        if (focused) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, alpha));
            c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill);
        } else {
            glass.draw(c, rect, rect.height() / 2, bg(), null, 0, w, h, alpha);
        }
        chipPaint.setColor(focused ? INK : WHITE);
        chipPaint.setAlpha(Math.round(255 * alpha));
        c.drawText(chip, tx + 15 * k, base - 2 * k, chipPaint);
    }

    /** Continue watching: what you were watching, its progress and Resume. */
    private void drawHero(Canvas c, float alpha) {
        WatchNext.Entry e = hero;
        float x = contentLeft();
        kickerPaint.setAlpha(Math.round(199 * alpha));
        c.drawText(Ui.tr("Continue watching").toUpperCase(Ui.locale()), x, 262 * k, kickerPaint);
        heroTitle.setAlpha(Math.round(255 * alpha));
        String title = fit(e.title, heroTitle, 1100 * k);
        c.drawText(title, x - Ui.leftBearing(heroTitle, title), 368 * k, heroTitle);
        heroMeta.setAlpha(Math.round(209 * alpha));
        c.drawText(fit(e.subtitle, heroMeta, 1000 * k), x, 426 * k, heroMeta);
        float by = 474 * k;
        if (e.progress >= 0) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0x40FFFFFF, alpha));
            rect.set(x, by - 3 * k, x + 380 * k, by + 3 * k);
            c.drawRoundRect(rect, 3 * k, 3 * k, fill);
            fill.setColor(Ui.alpha(WHITE, alpha));
            rect.right = x + 380 * k * e.progress;
            c.drawRoundRect(rect, 3 * k, 3 * k, fill);
            if (e.left >= 0) {
                smallPaint.setAlpha(Math.round(191 * alpha));
                c.drawText(Ui.trf("{n} min left", "n", Math.max(1, Math.round(e.left / 60_000f))), x + 398 * k, by + 8 * k, smallPaint);
            }
        }
        float f = zone == Z_HERO ? 1 : 0, grow = 1 + 0.05f * f;
        String resume = Ui.tr("Resume");
        float bw = buttonPaint.measureText(resume) + 110 * k, bh = 68 * k, bt = 521 * k;
        rect.set(x, bt, x + bw, bt + bh);
        int save = c.save();
        c.scale(grow, grow, rect.centerX(), rect.centerY());
        fill.setShader(null);
        fill.setColor(Ui.alpha(WHITE, alpha));
        c.drawRoundRect(rect, bh / 2, bh / 2, fill);
        if (f > 0) {
            ring.setColor(Ui.alpha(0x99FFFFFF, alpha));
            ring.setStrokeWidth(4 * k);
            rect2.set(rect.left - 6 * k, rect.top - 6 * k, rect.right + 6 * k, rect.bottom + 6 * k);
            c.drawRoundRect(rect2, bh / 2 + 6 * k, bh / 2 + 6 * k, ring);
        }
        Symbols.draw(getContext(), c, "play", x + 46 * k, rect.centerY(), 26 * k, Ui.alpha(INK, alpha));
        buttonPaint.setAlpha(Math.round(255 * alpha));
        c.drawText(resume, x + 74 * k, rect.centerY() + Ui.halfCap(buttonPaint), buttonPaint);
        c.restoreToCount(save);
    }

    /** The music: the cover (3× a tile) at the left, the song, its progress and ⏮ ⏯ ⏭ above the shelf. */
    private void drawMusic(Canvas c, float alpha) {
        NowPlaying.Track t = track;
        float m = Ui.clamp(music.value) * alpha;
        if (m <= 0.01f) return;
        // The cover grows out of the shelf.
        float size = COVER * k * (0.35f + 0.65f * Ui.clamp(music.value)), left = COVER_X * k, bottom = (COVER_TOP + COVER) * k;
        rect.set(left, bottom - size, left + size, bottom);
        float radius = 64 * k * (size / (COVER * k));
        drawSoftShadow(c, rect, m);
        if (coverShader != null && coverArt != null && !coverArt.isRecycled()) {
            float bw = coverArt.getWidth(), bh = coverArt.getHeight(), side = Math.min(bw, bh);
            src.set((bw - side) / 2, (bh - side) / 2, (bw + side) / 2, (bh + side) / 2);
            matrix.setRectToRect(src, rect, Matrix.ScaleToFit.FILL);
            coverShader.setLocalMatrix(matrix);
            image.setShader(coverShader);
            image.setAlpha(Math.round(255 * m));
            c.drawRoundRect(rect, radius, radius, image);
            image.setShader(null);
        } else {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF2A2C33, m));
            c.drawRoundRect(rect, radius, radius, fill);
            Symbols.draw(getContext(), c, "play", rect.centerX(), rect.centerY(), 80 * k, Ui.alpha(0xCCFFFFFF, m));
        }
        ring.setColor(Ui.alpha(0x33FFFFFF, m));
        ring.setStrokeWidth(2 * k);
        c.drawRoundRect(rect, radius, radius, ring);
        // The playing app's own tile as a badge in the corner.
        HomeApps.Item app = null;
        for (HomeApps.Item it : apps) if (t.pkg != null && t.pkg.equals(it.pkg)) app = it;
        if (app != null && app.squareShader != null) {
            float bs = 58 * k;
            rect2.set(rect.right - 22 * k - bs, rect.bottom - 22 * k - bs, rect.right - 22 * k, rect.bottom - 22 * k);
            drawSquare(c, app, rect2, bs / 2, m);
        }
        // The song.
        float x = TRACK_X * k;
        kickerPaint.setAlpha(Math.round(199 * m));
        c.drawText(trackKicker, x + 48 * k, 548 * k, kickerPaint);
        drawBars(c, x, 548 * k, m, t.playing);
        trackTitlePaint.setAlpha(Math.round(255 * m));
        c.drawText(trackTitle, x - Ui.leftBearing(trackTitlePaint, trackTitle), 616 * k, trackTitlePaint);
        trackArtistPaint.setAlpha(Math.round(217 * m));
        c.drawText(trackArtist, x, 656 * k, trackArtistPaint);
        // The progress, then the controls centered under it.
        float barL = x + 56 * k, barR = x + 576 * k, by = 690 * k;
        long pos = t.positionNow();
        timePaint.setAlpha(Math.round(204 * m));
        c.drawText(time(pos), x, by + 7 * k, timePaint);
        c.drawText(time(t.duration), barR + 14 * k, by + 7 * k, timePaint);
        fill.setShader(null);
        fill.setColor(Ui.alpha(0x40FFFFFF, m));
        rect.set(barL, by - 2.5f * k, barR, by + 2.5f * k);
        c.drawRoundRect(rect, 2.5f * k, 2.5f * k, fill);
        if (t.duration > 0 && pos >= 0) {
            fill.setColor(Ui.alpha(WHITE, m));
            rect.right = barL + (barR - barL) * Ui.clamp(pos / (float) t.duration);
            c.drawRoundRect(rect, 2.5f * k, 2.5f * k, fill);
        }
        float cx = (barL + barR) / 2, cy = 746 * k;
        drawControl(c, cx - 78 * k, cy, 29 * k, "prev", 1, m);
        drawControl(c, cx, cy, 34 * k, t.playing ? "pause" : "play", 2, m);
        drawControl(c, cx + 78 * k, cy, 29 * k, "next", 3, m);
    }

    private static String time(long ms) {
        if (ms < 0) return "–:––";
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** A small equalizer before "NOW PLAYING" (still while paused). */
    private void drawBars(Canvas c, float x, float base, float alpha, boolean playing) {
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF1ED760, alpha));
        float[] heights = {10, 18, 7, 14};
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < 4; i++) {
            float hh = heights[i] * k;
            if (playing) hh *= 0.6f + 0.4f * (float) Math.abs(Math.sin(now / (260.0 + i * 70) + i));
            rect.set(x + i * 9 * k, base - hh, x + i * 9 * k + 5 * k, base);
            c.drawRoundRect(rect, 2 * k, 2 * k, fill);
        }
        if (playing && running && !framing) postInvalidateDelayed(100);
    }

    private void drawControl(Canvas c, float cx, float cy, float r, String what, int index, float alpha) {
        float v = musicFocus[index].value, f = Ui.clamp(v), grow = 1 + 0.12f * v;
        float rr = r * grow;
        boolean light = index == 2 || f > 0.5f;
        fill.setShader(null);
        fill.setColor(Ui.alpha(light ? WHITE : 0x29FFFFFF, alpha));
        c.drawCircle(cx, cy, rr, fill);
        if (!light) {
            ring.setColor(Ui.alpha(0x47FFFFFF, alpha));
            ring.setStrokeWidth(1.5f * k);
            c.drawCircle(cx, cy, rr, ring);
        }
        if (f > 0.01f) {
            ring.setColor(Ui.alpha(0xB3FFFFFF, f * alpha));
            ring.setStrokeWidth(3 * k);
            c.drawCircle(cx, cy, rr + 6 * k, ring);
        }
        int ink = Ui.alpha(light ? INK : WHITE, alpha);
        fill.setColor(ink);
        float s = rr * 0.36f;
        if (what.equals("pause")) {
            rect.set(cx - s * 0.8f, cy - s, cx - s * 0.25f, cy + s);
            c.drawRoundRect(rect, s * 0.15f, s * 0.15f, fill);
            rect.set(cx + s * 0.25f, cy - s, cx + s * 0.8f, cy + s);
            c.drawRoundRect(rect, s * 0.15f, s * 0.15f, fill);
        } else if (what.equals("play")) {
            Symbols.draw(getContext(), c, "play", cx + 2 * k, cy, rr * 0.75f, ink);
        } else {
            float dir = what.equals("next") ? 1 : -1;
            triangle.reset();
            triangle.moveTo(cx - dir * s * 0.7f, cy - s * 0.75f);
            triangle.lineTo(cx + dir * s * 0.45f, cy);
            triangle.lineTo(cx - dir * s * 0.7f, cy + s * 0.75f);
            triangle.close();
            c.drawPath(triangle, fill);
            rect.set(cx + dir * s * 0.5f - s * 0.12f, cy - s * 0.75f, cx + dir * s * 0.5f + s * 0.12f, cy + s * 0.75f);
            c.drawRect(rect, fill);
        }
    }

    private void drawSoftShadow(Canvas c, RectF r, float alpha) {
        if (tileShadow == null) return;
        float pad = r.width() * (tileShadow.getWidth() / (float) Math.round(TILE * k) - 1) / 2;
        float drop = 12 * k * (r.width() / (TILE * k));
        rect2.set(r.left - pad, r.top - pad + drop, r.right + pad, r.bottom + pad + drop);
        shadowPaint.setAlpha(Math.round(110 * alpha));
        c.drawBitmap(tileShadow, null, rect2, shadowPaint);
    }

    /** The shelf: a clear glass bar along the bottom with the apps; the ones that don't fit become dots. */
    private void drawShelf(Canvas c, float m) {
        float left = dockLeft.value, right = dockRight.value, top = DOCK_TOP * k;
        rect.set(left, top, right, top + DOCK_H * k);
        glass.draw(c, rect, DOCK_R * k, bg(), bg2(), incoming == null ? 0 : fadeT(), w, h, 1);
        if (shelf.isEmpty()) return;
        float gap = (GAP + (MUSIC_GAP - GAP) * m) * k, pad = (PAD + (MUSIC_PAD - PAD) * m) * k;
        float size = TILE * k, step = size + gap, tileTop = top + (DOCK_H * k - size) / 2;
        int slots = slots();
        boolean dotsLeft = shelfFirst > 0, dotsRight = shelfFirst + slots < shelf.size();
        float x0 = left + pad + (dotsLeft ? DOTS * k : 0) - (shelfScroll.value - shelfFirst) * step;
        int save = c.save();
        c.clipRect(left + pad - 40 * k + (dotsLeft ? DOTS * k : 0), top - 80 * k,
                right - pad + 40 * k - (dotsRight ? DOTS * k : 0), top + DOCK_H * k + 80 * k);
        int focused = -1;
        for (int i = 0; i < shelf.size(); i++) {
            float x = x0 + (i - shelfFirst) * step + tileSlide[i].value;
            if (x > right || x + size < left) continue;
            if (zone == Z_SHELF && i == shelfIndex) {
                focused = i;
                continue;
            }
            drawTile(c, i, x, tileTop, size);
        }
        c.restoreToCount(save);
        if (focused >= 0) drawTile(c, focused, x0 + (focused - shelfFirst) * step + tileSlide[focused].value, tileTop, size);
        if (dotsLeft) drawDots(c, left + pad + 12 * k, top + DOCK_H * k / 2, 1);
        if (dotsRight) drawDots(c, right - pad - 12 * k, top + DOCK_H * k / 2, -1);
        // The focused app's name under the bar.
        if (zone == Z_SHELF && focused >= 0 && focused < shelfLabels.length) {
            float f = Ui.clamp(tileFocus[focused].value);
            float cx = x0 + (focused - shelfFirst) * step + tileSlide[focused].value + size / 2;
            labelPaint.setAlpha(Math.round(255 * f));
            c.drawText(shelfLabels[focused], cx, 1056 * k, labelPaint);
        } else if (zone == Z_MUSIC && musicIndex == 0 && track != null) {
            labelPaint.setAlpha(255);
            c.drawText(track.app, (COVER_X + COVER / 2) * k, 1056 * k, labelPaint);
        }
    }

    /** Three dots, getting smaller toward the edge: "more apps this way". */
    private void drawDots(Canvas c, float x, float cy, int inward) {
        fill.setShader(null);
        float[] sizes = {2.5f, 4, 7};
        int[] alphas = {0x38, 0x66, 0xBF};
        for (int i = 0; i < 3; i++) {
            fill.setColor((alphas[i] << 24) | 0xFFFFFF);
            c.drawCircle(x + inward * i * 13 * k, cy, sizes[i] * k, fill);
        }
    }

    /** A tile: a soft shadow and a faint edge; focused, it grows, rises, gets a white ring and a shine sweeps across. */
    private void drawTile(Canvas c, int i, float x, float top, float size) {
        HomeApps.Item it = shelf.get(i);
        float v = tileFocus[i].value, f = Ui.clamp(v);
        boolean lifted = reordering && i == shelfIndex;
        float grow = 1 + GROW * v + (lifted ? 0.06f * lift.value : 0);
        float cx = x + size / 2, cy = top + size / 2 - 10 * k * v - (lifted ? 16 * k * lift.value : 0);
        float half = size * grow / 2;
        rect.set(cx - half, cy - half, cx + half, cy + half);
        drawSoftShadow(c, rect, 0.5f + 0.5f * f);
        float radius = TILE_R * k * grow;
        drawSquare(c, it, rect, radius, 1);
        ring.setStrokeWidth(Math.max(1, 1.5f * k));
        ring.setColor(0x24FFFFFF);
        c.drawRoundRect(rect, radius, radius, ring);
        if (f > 0.01f) {
            // The shine: a band of light sweeping across the tile.
            long t = (SystemClock.uptimeMillis() - shineAt) % SHINE_EVERY;
            if (t < SHINE_MS && shineShader != null) {
                float p = t / (float) SHINE_MS, bw = rect.width() * 0.9f;
                matrix.setScale(bw, 1);
                matrix.postRotate(-24);
                matrix.postTranslate(rect.left - bw + (rect.width() + 2 * bw) * p, rect.top);
                shineShader.setLocalMatrix(matrix);
                shine.setAlpha(Math.round(255 * f));
                c.drawRoundRect(rect, radius, radius, shine);
            }
            ring.setColor(Ui.alpha(0xF2FFFFFF, f));
            ring.setStrokeWidth(4 * k);
            float o = 7 * k;
            rect.inset(-o, -o);
            c.drawRoundRect(rect, radius + o, radius + o, ring);
        }
    }

    private void drawSquare(Canvas c, HomeApps.Item it, RectF r, float radius, float alpha) {
        BitmapShader s = it.squareShader;
        if (s == null) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF2A2C33, alpha));
            c.drawRoundRect(r, radius, radius, fill);
            return;
        }
        src.set(0, 0, it.square.getWidth(), it.square.getHeight());
        matrix.setRectToRect(src, r, Matrix.ScaleToFit.FILL);
        s.setLocalMatrix(matrix);
        image.setShader(s);
        image.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(r, radius, radius, image);
        image.setShader(null);
    }

    /** Below the shelf: Continue watching, the weather, then all the apps. */
    private void drawRows(Canvas c, float scroll) {
        float x = contentLeft();
        if (!watchNext.isEmpty()) {
            float top = rowTop();
            c.drawText(Ui.tr("Continue watching"), x, top + 30 * k, sectionPaint);
            float cardsTop = top + 56 * k, step = (CARD_W + CARD_GAP) * k, x0 = x - 16 * k - rowScroll.value;
            int focused = -1;
            for (int i = 0; i < watchNext.size(); i++) {
                float cx = x0 + i * step;
                if (cx > w || cx + CARD_W * k < 0) continue;
                if (zone == Z_ROW && i == rowIndex) {
                    focused = i;
                    continue;
                }
                drawCard(c, i, cx, cardsTop);
            }
            if (focused >= 0) drawCard(c, focused, x0 + focused * step, cardsTop);
        }
        if (showWeather()) drawWeatherPanel(c, weatherTop());
        float gt = gridTop();
        c.drawText(Ui.tr("All Apps"), x, gt - 26 * k, sectionPaint);
        float size = TILE * k, gx = x - 26 * k;
        for (int i = 0; i < grid.size(); i++) {
            float tx = gx + (i % GRID_COLS) * (TILE + GAP) * k, ty = gridRowTop(i / GRID_COLS);
            if (ty - scroll > h || ty + size + 60 * k - scroll < 0) continue;
            float v = gridFocus[i].value, f = Ui.clamp(v), grow = 1 + 0.1f * v;
            float cx = tx + size / 2, cy = ty + size / 2, half = size * grow / 2;
            rect.set(cx - half, cy - half, cx + half, cy + half);
            drawSoftShadow(c, rect, 0.4f + 0.6f * f);
            drawSquare(c, grid.get(i), rect, TILE_R * k * grow, 1);
            if (f > 0.01f) {
                ring.setColor(Ui.alpha(0xF2FFFFFF, f));
                ring.setStrokeWidth(4 * k);
                rect.inset(-7 * k, -7 * k);
                c.drawRoundRect(rect, TILE_R * k * grow + 7 * k, TILE_R * k * grow + 7 * k, ring);
            }
            gridLabelPaint.setColor(blend(0xCCFFFFFF, WHITE, f));
            if (i < gridLabels.length) c.drawText(gridLabels[i], cx, ty + size + 34 * k, gridLabelPaint);
        }
    }

    private void drawCard(Canvas c, int i, float x, float top) {
        WatchNext.Entry e = watchNext.get(i);
        float v = cardFocus[i].value, f = Ui.clamp(v), grow = 1 + 0.06f * v;
        float cw = CARD_W * k, ch = CARD_H * k, cx = x + cw / 2, cy = top + ch / 2;
        rect.set(cx - cw * grow / 2, cy - ch * grow / 2, cx + cw * grow / 2, cy + ch * grow / 2);
        drawSoftShadow(c, rect, 0.5f + 0.5f * f);
        float radius = CARD_R * k * grow;
        Bitmap b = e.image == null ? null : cardImages.get(e.image);
        BitmapShader s = b == null ? null : cardShaders.get(b);
        if (s != null) {
            src.set(0, 0, b.getWidth(), b.getHeight());
            matrix.setRectToRect(src, rect, Matrix.ScaleToFit.FILL);
            s.setLocalMatrix(matrix);
            image.setShader(s);
            image.setAlpha(255);
            c.drawRoundRect(rect, radius, radius, image);
            image.setShader(null);
        } else {
            fill.setShader(null);
            fill.setColor(0xFF24252B);
            c.drawRoundRect(rect, radius, radius, fill);
        }
        // A dark fade at the bottom for the title, then the progress.
        matrix.setScale(1, rect.height());
        matrix.postTranslate(0, rect.top);
        cardFade.setLocalMatrix(matrix);
        fill.setShader(cardFade);
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setShader(null);
        c.drawText(fit(e.title, cardTitlePaint, rect.width() - 44 * k), rect.left + 22 * k, rect.bottom - 40 * k, cardTitlePaint);
        if (e.progress >= 0) {
            float pad = 20 * k, by = rect.bottom - 22 * k;
            fill.setColor(0x73000000);
            rect2.set(rect.left + pad, by, rect.right - pad, by + 6 * k);
            c.drawRoundRect(rect2, 3 * k, 3 * k, fill);
            fill.setColor(WHITE);
            rect2.right = rect2.left + (rect.width() - 2 * pad) * e.progress;
            c.drawRoundRect(rect2, 3 * k, 3 * k, fill);
        }
        if (f > 0.01f) {
            ring.setColor(Ui.alpha(0xF2FFFFFF, f));
            ring.setStrokeWidth(4 * k);
            rect.inset(-7 * k, -7 * k);
            c.drawRoundRect(rect, radius + 7 * k, radius + 7 * k, ring);
        }
    }

    /** The weather panel: now, today's high and low, sunrise and sunset, and the next hours. */
    private void drawWeatherPanel(Canvas c, float top) {
        float x = contentLeft();
        c.drawText(Ui.tr("Weather"), x, top + 30 * k, sectionPaint);
        float pl = x - 26 * k, pt = top + 56 * k, pr = pl + 1482 * k;
        rect.set(pl, pt, pr, pt + WEATHER_H * k);
        glass.draw(c, rect, 48 * k, null, null, 0, w, h, 1);
        fill.setShader(null);
        fill.setColor(0x26000000);
        c.drawRoundRect(rect, 48 * k, 48 * k, fill);
        HomeData.Weather wx = data.weather;
        float ix = pl + 90 * k, iy = pt + 110 * k;
        Symbols.draw(getContext(), c, weatherSymbol != null ? weatherSymbol : "sunny", ix, iy, 84 * k, WHITE);
        c.drawText(tempText, ix + 70 * k, iy + 34 * k, bigTempPaint);
        String line = (Prefs.city().isEmpty() ? "" : Prefs.city() + "  ·  ") + condText + "  ·  " + HomeData.highLow(wx, "  ");
        c.drawText(line, ix + 74 * k, iy + 82 * k, heroMeta);
        if (wx.sunrise != 0) {
            float sx = pr - 330 * k;
            Symbols.draw(getContext(), c, "sunrise", sx, iy, 30 * k, WHITE);
            c.drawText(Ui.time(wx.sunrise), sx + 26 * k, iy + 8 * k, smallPaint);
            Symbols.draw(getContext(), c, "sunset", sx + 170 * k, iy, 30 * k, WHITE);
            c.drawText(Ui.time(wx.sunset), sx + 196 * k, iy + 8 * k, smallPaint);
        }
        int n = Math.min(10, wx.hours.size());
        if (n == 0) return;
        float colW = (pr - pl - 100 * k) / n, hy = pt + 236 * k;
        for (int i = 0; i < n; i++) {
            float cx = pl + 50 * k + colW * (i + 0.5f);
            c.drawText(HomeData.hour(wx.hours.get(i)), cx, hy, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), cx, hy + 40 * k, 34 * k, WHITE);
            c.drawText(HomeData.degrees(wx.hourTemp.get(i)), cx, hy + 92 * k, hourPaint);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    private final Runnable wakeUp = this::wake;

    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) toast = null;
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                int max = Math.round(760 * k);
                int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(next, toastPaint)));
                toast = StaticLayout.Builder.obtain(next, 0, next.length(), toastPaint, Math.max(1, width))
                        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build();
                toastAt = up;
                handler.postDelayed(wakeUp, TOAST_MS - 450);
                handler.postDelayed(wakeUp, TOAST_MS + 20);
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        return age < 350 || age > TOAST_MS - 450;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 30 * k, padY = 20 * k, tw = toast.getWidth(), th = toast.getHeight();
        float top = 44 * k - (1 - a) * 16 * k;
        rect.set((w - tw) / 2f - padX, top, (w + tw) / 2f + padX, top + th + 2 * padY);
        int save = c.saveLayerAlpha(rect.left - 4, rect.top - 4, rect.right + 4, rect.bottom + 4, Math.round(255 * a));
        fill.setShader(null);
        fill.setColor(0xE61C1C21);
        c.drawRoundRect(rect, Math.min(rect.height() / 2, 34 * k), Math.min(rect.height() / 2, 34 * k), fill);
        c.translate(rect.left + padX, rect.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Keys: the arrows, OK and Back (the others go to the system)

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (track == null) return false;
                if (down && e.getRepeatCount() == 0) nowPlaying.playPause();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = zone == Z_GRID ? canToggleShelf(gridIndex) : canReorder();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (zone == Z_RAIL) {
            zone = lastZone == Z_RAIL ? Z_SHELF : lastZone;
            focusChanged();
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            focusChanged();
        } else if (shelfIndex != 0) {
            shelfIndex = 0;
            focusChanged();
        }
    }

    /** The zone above the shelf: the music controls, Resume, or the photo's ◀ ▶. */
    private int aboveShelf() {
        if (track != null) return Z_MUSIC;
        if (hero != null) return Z_HERO;
        Backing b = incoming != null ? incoming : backing;
        return b != null && !b.title.isEmpty() ? Z_CAPTION : -1;
    }

    private void move(int dx, int dy) {
        int beforeZone = zone, before = focusIndex();
        switch (zone) {
            case Z_RAIL:
                if (dy != 0) railIndex = Math.max(0, Math.min(rail.size() - 1, railIndex + dy));
                if (dx > 0) zone = lastZone == Z_RAIL ? Z_SHELF : lastZone;
                break;
            case Z_CAPTION:
                if (dx != 0) {
                    anotherPhoto(dx);
                    return;
                }
                if (dy > 0) zone = Z_SHELF;
                break;
            case Z_HERO:
                if (dx < 0) openRail();
                if (dy > 0) zone = Z_SHELF;
                break;
            case Z_MUSIC:
                if (dx != 0) {
                    int next = musicIndex + dx;
                    if (next < 0) openRail();
                    else if (next > 3) {
                        zone = Z_SHELF;
                        shelfIndex = shelfFirst;
                    } else musicIndex = next;
                }
                if (dy > 0) zone = Z_SHELF;
                if (dy < 0 && musicIndex == 0) musicIndex = 2;
                break;
            case Z_SHELF:
                if (dx < 0 && shelfIndex == 0) {
                    if (track != null) {
                        zone = Z_MUSIC;
                        musicIndex = 0;
                    } else {
                        openRail();
                    }
                } else if (dx != 0) {
                    shelfIndex = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                }
                if (dy < 0 && aboveShelf() >= 0) {
                    zone = aboveShelf();
                    if (zone == Z_MUSIC) musicIndex = 2;
                }
                if (dy > 0) zone = !watchNext.isEmpty() ? Z_ROW : !grid.isEmpty() ? Z_GRID : Z_SHELF;
                break;
            case Z_ROW:
                if (dx < 0 && rowIndex == 0) openRail();
                else if (dx != 0) rowIndex = Math.max(0, Math.min(watchNext.size() - 1, rowIndex + dx));
                if (dy < 0) zone = Z_SHELF;
                if (dy > 0 && !grid.isEmpty()) {
                    zone = Z_GRID;
                    gridIndex = Math.min(grid.size() - 1, Math.min(GRID_COLS - 1, rowIndex * 2));
                }
                break;
            case Z_GRID:
                if (dx < 0 && gridIndex % GRID_COLS == 0) openRail();
                else if (dx != 0) {
                    int col = gridIndex % GRID_COLS + dx;
                    if (col >= 0 && col < GRID_COLS && gridIndex + dx < grid.size()) gridIndex += dx;
                }
                if (dy != 0) {
                    int next = gridIndex + dy * GRID_COLS;
                    if (next >= 0 && next < grid.size()) gridIndex = next;
                    else if (dy < 0) zone = !watchNext.isEmpty() ? Z_ROW : Z_SHELF;
                    else if (gridIndex / GRID_COLS < (grid.size() - 1) / GRID_COLS) gridIndex = grid.size() - 1;
                }
                break;
            default:
                break;
        }
        if (zone != beforeZone || focusIndex() != before) focusChanged();
    }

    private void openRail() {
        if (rail.isEmpty()) return;
        lastZone = zone;
        zone = Z_RAIL;
        railIndex = Math.min(railIndex, rail.size() - 1);
    }

    private int focusIndex() {
        switch (zone) {
            case Z_RAIL: return railIndex;
            case Z_MUSIC: return musicIndex;
            case Z_ROW: return rowIndex;
            case Z_GRID: return gridIndex;
            case Z_SHELF: return shelfIndex;
            default: return 0;
        }
    }

    private void focusChanged() {
        click.move();
        shineAt = SystemClock.uptimeMillis();
        retarget();
        spoken.focusMoved();
    }

    private void anotherPhoto(int dir) {
        click.move();
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            DailyPhoto.next(c, dir);
            handler.post(() -> rebake(false));
        });
    }

    private void activate() {
        switch (zone) {
            case Z_RAIL: {
                HomeApps.Item it = rail.get(railIndex);
                if (it.kind == HomeApps.HOME) {
                    click.open();
                    reset(true);
                } else {
                    open(it, rectOf(Z_RAIL, railIndex));
                }
                break;
            }
            case Z_CAPTION:
                anotherPhoto(1);
                break;
            case Z_HERO:
                if (hero != null) openEntry(hero);
                break;
            case Z_MUSIC:
                click.open();
                if (musicIndex == 0 && track != null) {
                    if (!NowPlaying.open(getContext(), track)) showToast(Ui.trf("Couldn't open {app}.", "app", track.app));
                } else if (musicIndex == 1) {
                    nowPlaying.previous();
                } else if (musicIndex == 2) {
                    nowPlaying.playPause();
                } else {
                    nowPlaying.next();
                }
                break;
            case Z_SHELF:
                if (shelfIndex < shelf.size()) open(shelf.get(shelfIndex), rectOf(Z_SHELF, shelfIndex));
                break;
            case Z_ROW:
                if (rowIndex < watchNext.size()) openEntry(watchNext.get(rowIndex));
                break;
            case Z_GRID:
                if (gridIndex < grid.size()) open(grid.get(gridIndex), rectOf(Z_GRID, gridIndex));
                break;
            default:
                break;
        }
    }

    private void openEntry(WatchNext.Entry e) {
        click.open();
        if (e.intent != null) {
            try {
                activity.startActivity(new Intent(e.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
            }
        }
        HomeApps.Item app = HomeApps.app(e.pkg, e.title);
        RectF from = rectOf(zone, focusIndex());
        if (!HomeApps.open(activity, app, this, Math.round(from.left), Math.round(from.top), Math.max(1, Math.round(from.width())), Math.max(1, Math.round(from.height())))) {
            showToast(Ui.trf("Couldn't open {app}.", "app", e.title));
        }
    }

    private void open(HomeApps.Item it, RectF from) {
        click.open();
        if (!HomeApps.open(activity, it, this, Math.round(from.left), Math.round(from.top), Math.max(1, Math.round(from.width())), Math.max(1, Math.round(from.height())))) {
            showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
        }
    }

    // MARK: - Holding OK: move an app on the shelf, or put an app from All Apps on the shelf

    private void hold() {
        if (zone == Z_GRID) toggleShelf(); else startReorder();
    }

    private boolean canToggleShelf(int i) {
        if (i < 0 || i >= grid.size()) return false;
        HomeApps.Item it = grid.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        final HomeApps.Item it = grid.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int max = HomeApps.maxShelf(c), shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= max) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= max) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Free My TV › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);
            handler.post(() -> {
                showToast(text);
                appsStale = true;
                reload();
            });
        });
    }

    private boolean canReorder() {
        if (zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        reordering = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget();
        spoken.focusMoved();
    }

    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = (i - j) * (TILE + GAP) * k;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        shelfIndex = j;
        fitLabels();
        click.move();
        retarget();
        spoken.focusMoved();
    }

    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget();
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) if (it.kind == HomeApps.APP && it.pkg != null) order.add(it.pkg);
        if (track != null && track.pkg != null) {
            // The playing app sits out of the shelf for now: it keeps its place.
            for (int i = 0; i < apps.size(); i++) if (track.pkg.equals(apps.get(i).pkg)) order.add(Math.min(i, order.size()), track.pkg);
        }
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    private void cancelReorder(boolean snap) {
        if (!reordering) return;
        reordering = false;
        HomeApps.Item lifted = shelf.get(shelfIndex);
        List<HomeApps.Item> origin = reorderOrigin;
        reorderOrigin = null;
        float step = (TILE + GAP) * k;
        Spring[] focus = new Spring[origin.size()], slide = new Spring[origin.size()];
        for (int i = 0; i < origin.size(); i++) {
            int now = shelf.indexOf(origin.get(i));
            focus[i] = tileFocus[now];
            slide[i] = tileSlide[now];
            slide[i].value += (now - i) * step;
            if (snap) slide[i].snap(0);
        }
        shelf = new ArrayList<>(origin);
        tileFocus = focus;
        tileSlide = slide;
        fitLabels();
        shelfIndex = shelf.indexOf(lifted);
        retarget();
        spoken.focusMoved();
    }

    // MARK: - TalkBack (each item the remote can reach is a virtual node)

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private static int a11yId(int zone, int i) {
        return (zone + 1) * 1000 + i + 1;
    }

    private void a11yItems(List<Integer> out) {
        for (int i = 0; i < rail.size(); i++) out.add(a11yId(Z_RAIL, i));
        int above = aboveShelf();
        if (above == Z_MUSIC) for (int i = 0; i < 4; i++) out.add(a11yId(Z_MUSIC, i));
        else if (above >= 0) out.add(a11yId(above, 0));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < watchNext.size(); i++) out.add(a11yId(Z_ROW, i));
        for (int i = 0; i < grid.size(); i++) out.add(a11yId(Z_GRID, i));
    }

    private String a11yText(int id) {
        int z = id / 1000 - 1, i = id % 1000 - 1;
        switch (z) {
            case Z_RAIL: return i < rail.size() ? rail.get(i).label : "";
            case Z_CAPTION: return Ui.tr("Another photo today");
            case Z_HERO: return hero == null ? "" : Ui.tr("Resume") + ", " + hero.title;
            case Z_MUSIC: {
                if (track == null) return "";
                String[] names = {track.title + ", " + track.artist, "⏮", track.playing ? "⏸" : "▶", "⏭"};
                return names[Math.max(0, Math.min(3, i))];
            }
            case Z_SHELF: {
                if (i >= shelf.size()) return "";
                String label = shelf.get(i).label;
                return reordering && i == shelfIndex
                        ? Ui.trf("Moving {app}, position {n} of {total}", "app", label, "n", i + 1, "total", shelf.size()) : label;
            }
            case Z_ROW: return i < watchNext.size() ? Ui.tr("Continue watching") + ", " + watchNext.get(i).title : "";
            case Z_GRID: return i < grid.size() ? grid.get(i).label : "";
            default: return "";
        }
    }

    private boolean a11yBounds(int id, Rect out) {
        RectF r = rectOf(id / 1000 - 1, id % 1000 - 1);
        if (r.isEmpty()) return false;
        r.round(out);
        return true;
    }

    private void a11yFocus(int id) {
        int z = id / 1000 - 1, i = id % 1000 - 1;
        zone = z;
        switch (z) {
            case Z_RAIL: railIndex = i; break;
            case Z_MUSIC: musicIndex = i; break;
            case Z_SHELF: shelfIndex = i; break;
            case Z_ROW: rowIndex = i; break;
            case Z_GRID: gridIndex = i; break;
            default: break;
        }
        retarget();
    }

    /** Where an item is on screen now (a new RectF: TalkBack and the opening animation keep it). */
    private RectF rectOf(int z, int i) {
        RectF r = new RectF();
        float scroll = page.value;
        switch (z) {
            case Z_RAIL:
                r.set(RAIL_X * k, (RAIL_CY - 200 + i * 62) * k, (RAIL_X + RAIL_W) * k, (RAIL_CY - 144 + i * 62) * k);
                break;
            case Z_SHELF: {
                float gap = track != null ? MUSIC_GAP : GAP, pad = track != null ? MUSIC_PAD : PAD;
                float x = dockLeft.value + pad * k + (shelfFirst > 0 ? DOTS * k : 0) + (i - shelfFirst) * (TILE + gap) * k;
                float top = DOCK_TOP * k + (DOCK_H - TILE) * k / 2 - scroll;
                r.set(x, top, x + TILE * k, top + TILE * k);
                break;
            }
            case Z_MUSIC:
                if (i == 0) r.set(COVER_X * k, COVER_TOP * k - scroll, (COVER_X + COVER) * k, (COVER_TOP + COVER) * k - scroll);
                else {
                    float cx = (TRACK_X + 316) * k + (i - 2) * 78 * k;
                    r.set(cx - 30 * k, 716 * k - scroll, cx + 30 * k, 776 * k - scroll);
                }
                break;
            case Z_HERO:
                r.set(contentLeft(), 521 * k - scroll, contentLeft() + 220 * k, 589 * k - scroll);
                break;
            case Z_CAPTION:
                r.set(contentLeft(), 747 * k - scroll, contentLeft() + 860 * k, 783 * k - scroll);
                break;
            case Z_ROW: {
                float x = contentLeft() - 16 * k - rowScroll.value + i * (CARD_W + CARD_GAP) * k, top = rowTop() + 56 * k - scroll;
                r.set(x, top, x + CARD_W * k, top + CARD_H * k);
                break;
            }
            case Z_GRID: {
                float x = contentLeft() - 26 * k + (i % GRID_COLS) * (TILE + GAP) * k, top = gridRowTop(i / GRID_COLS) - scroll;
                r.set(x, top, x + TILE * k, top + TILE * k);
                break;
            }
            default:
                break;
        }
        return r;
    }

    // MARK: - Helpers

    private static String fit(String s, TextPaint p, float width) {
        if (s == null) return "";
        return TextUtils.ellipsize(s, p, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }

    private static int blend(int a, int b, float f) {
        f = Ui.clamp(f);
        int out = 0;
        for (int s = 0; s < 32; s += 8) {
            int x = (a >>> s) & 255, y = (b >>> s) & 255;
            out |= Math.round(x + (y - x) * f) << s;
        }
        return out;
    }
}
