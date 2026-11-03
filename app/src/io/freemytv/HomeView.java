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
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
 * The home screen: dark, clean and flat, drawn by hand on one Canvas.
 *  - the background: a photo that changes every 30 s with a slow crossfade (your own photos, else the picks' images,
 *    else soft color gradients), darkened where the text sits;
 *  - top left, the big clock with the date and the weather; top right, round buttons: Wi-Fi, Live TV and TV Settings;
 *  - the shelf: the apps as rounded squares (the focused one grows, with a white ring and its name below), then All Apps;
 *  - below it, always there, the inputs (Live TV and every HDMI input, the ones with no device dimmed); at the right,
 *    what's playing (cover, title, artist), only while something plays;
 *  - ↓ from there scrolls down to the rows: Continue watching (the apps' Watch Next list), then movies and shows for you.
 * Measurements are in a 1920×1080 space (k = width / 1920).
 */
final class HomeView extends View implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener,
        NowPlaying.Listener {
    private static final float M = 96, RIGHT = 1824, CONTENT_W = RIGHT - M;
    // Top: the round buttons at the right, the clock block at the left.
    private static final float TOP_CY = 96, TOP_D = 68, TOP_GAP = 20;
    private static final float DATE_BASE = 150, CLOCK_BASE = 332, WEATHER_BASE = 410;
    // The shelf: square tiles; the focused one grows by GROW.
    private static final float SHELF_TOP = 500, TILE = 160, TILE_GAP = 30, TILE_R = 36, GROW = 0.1f;
    private static final int SHELF_SLOTS = 9;
    // Inputs and the now playing card, side by side under the shelf.
    private static final float INPUTS_TITLE = 790, CHIPS_TOP = 814, CHIP_H = 76;
    private static final float NP_W = 600, NP_H = 132, NP_TOP = 786, NP_ART = 108;
    private static final float PEEK_BASE = 1028;
    // The rows below (page coordinates: they start under the first screen).
    private static final float ROWS_TOP = 1100, ROW_H = 400, CARD_W = 400, CARD_H = 225, CARD_GAP = 32, CARD_R = 22;
    private static final int ROW_SLOTS = 4;
    // All Apps.
    private static final int COLS = 8;
    private static final float GRID_TILE = 150, GRID_STEP_X = 210, GRID_ROW = 236, GRID_TOP = 196;
    private static final int GRID_VISIBLE_ROWS = 4;

    private static final long BG_EVERY = 30_000, BG_FADE = 1800, TOAST_MS = 6000, HOLD_MS = 550, BURST_MS = 1500;
    private static final int Z_TOP = 0, Z_SHELF = 1, Z_INPUTS = 2, Z_ROWS = 3;
    private static final int WHITE = 0xFFF5F5F7, INK = 0xFF111114;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final ExecutorService IMAGES = Executors.newSingleThreadExecutor();
    /** Square tiles by package and size, kept across visits (the icon drawing is the slow part of a reload). */
    private static final Map<String, Bitmap> SQUARES = new LinkedHashMap<>(64, 0.75f, true);
    /** Where the background rotation is (across visits, so coming back home shows the next photo). */
    private static int bgNext;

    private final Activity activity;
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final NowPlaying nowPlaying;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final LauncherApps launcherApps;
    private final ConnectivityManager connectivity;
    private final Spoken spoken;

    private float k = 1;
    private int w, h;
    private boolean running, framing;
    private long lastFrame, resumedAt;

    // Focus
    private int zone = Z_SHELF, topIndex, shelfIndex, inputIndex, rowIndex, gridIndex;
    private boolean sheetOpen;

    // Top buttons
    private static final int TOP_WIFI = 0, TOP_LIVE = 1, TOP_SETTINGS = 2;
    private final List<Integer> topKeys = new ArrayList<>();
    private final Spring[] topFocus = {spring(), spring(), spring()};
    private final RectF[] topRects = {new RectF(), new RectF(), new RectF()};
    private boolean online = true, wifi = true;

    // Shelf (the apps, then All Apps)
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0], tileSlide = new Spring[0];
    private final Spring shelfScroll = new Spring(0.4f, 1f, 0);
    private int shelfFirst;
    private String[] shelfLabels = new String[0];

    // Inputs
    private List<HomeApps.Item> inputs = new ArrayList<>();
    private Spring[] chipFocus = new Spring[0];
    private float[] chipX = new float[0], chipW = new float[0];
    private String[] chipText = new String[0];
    private final Spring chipScroll = new Spring(0.4f, 1f, 0);

    // Now playing
    private NowPlaying.Track track;
    private final Spring npShow = new Spring(0.45f, 1f, 0), npFocus = spring();
    private BitmapShader npArt;
    private Bitmap npArtBitmap;
    private String npTitle = "", npArtist = "", npLabel = "";

    // Rows below the inputs
    private static final class Card {
        String title = "", subtitle = "", image, pkg, uri;
        Intent intent;
        File file;
        float progress = -1;
        Bitmap bitmap;
        BitmapShader shader;
        String titleFit, subtitleFit;
    }

    private static final class Row {
        final String title;
        final List<Card> cards;
        final Spring[] focus;
        final Spring scroll = new Spring(0.4f, 1f, 0);
        int index, first;

        Row(String title, List<Card> cards) {
            this.title = title;
            this.cards = cards;
            focus = new Spring[cards.size()];
            for (int i = 0; i < focus.length; i++) focus[i] = spring();
        }
    }

    private List<Row> rows = new ArrayList<>();
    /** The cards' images by cardKey (the rows are made again often; the images stay), and those being decoded. */
    private final Map<String, Card> cardImages = new HashMap<>();
    private final Set<String> cardsPending = new HashSet<>();
    private List<WatchNext.Entry> watchNext = new ArrayList<>();
    private final Spring page = new Spring(0.5f, 1f, 0);

    // All Apps
    private List<HomeApps.Item> grid = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels = new String[0];
    private final Spring sheet = new Spring(0.38f, 1f, 0), gridScroll = new Spring(0.4f, 1f, 0);
    private int gridFirstRow;

    // Moving an app on the shelf
    private boolean reordering;
    private List<HomeApps.Item> reorderOrigin;
    private final Spring lift = spring();

    // OK held
    private boolean okDown, okHeld, holdArmed, backDown;
    private long toggledAt;
    private final Runnable holdStart = this::hold;

    // Background
    private Bitmap bg, bgIncoming;
    private long bgFadeAt;
    private boolean bgLoading;
    private int bgGeneration;

    // Clock and weather
    private String clockText = "", dateText = "", weatherText = "", weatherSymbol;
    private long clockMinute = -1;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Lists
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    // Paints (made at layout)
    private TextPaint datePaint, clockPaint, weatherPaint, labelPaint, chipPaint, sectionPaint, cardTitlePaint,
            cardSubPaint, npTitlePaint, npArtistPaint, npSmallPaint, toastPaint, gridLabelPaint, sheetTitlePaint, tipPaint;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap shadow;
    private final RectF rect = new RectF(), src = new RectF(), oval = new RectF();
    private final Rect dst = new Rect();
    private final Matrix matrix = new Matrix();

    private static Spring spring() {
        return new Spring(0.28f, 0.86f, 0);
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
        connectivity = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        spoken = new Spoken(this, new Spoken.Source() {
            @Override public void items(List<Integer> out) { a11yItems(out); }
            @Override public String text(int id) { return a11yText(id); }
            @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
            @Override public int focused() { return a11yFocused(); }
            @Override public void focus(int id) { a11yFocus(id); }
            @Override public void click(int id) { activate(); }
            @Override public boolean holdable(int id) { return id / 1000 == A11Y_GRID && canToggleShelf(id % 1000 - 1); }
            @Override public void hold(int id) { a11yHold(); }
        });
        setFocusable(true);
        setContentDescription("Free My TV");
        image.setDither(true);
        ring.setStyle(Paint.Style.STROKE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        topKeys.add(TOP_WIFI);
        if (HomeApps.liveTv(a) != null) topKeys.add(TOP_LIVE);
        topKeys.add(TOP_SETTINGS);
        // First time: right away (a cold start opens with the apps there); the rest comes from reload().
        setShelf(HomeApps.shelf(a, false));
        setInputs(inputList(a));
        updateClock();
        updateWeather();
    }

    @Override protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        w = width;
        h = height;
        k = width / 1920f;
        makePaints();
        layoutChips();
        fitShelfLabels();
        if (!grid.isEmpty()) setGrid(grid);
        for (HomeApps.Item it : shelf) {   // made again at the new size
            it.square = null;
            it.squareShader = null;
        }
        loadSquares(shelf, tilePx());
        if (!grid.isEmpty()) loadSquares(grid, gridPx());
        refreshRowText();
        updateTrackText();
        bg = bgIncoming = null;
        nextBackground(true);
    }

    private void makePaints() {
        datePaint = paint(Ui.medium, 34, 0xD9FFFFFF);
        clockPaint = paint(Ui.light, 196, WHITE);
        clockPaint.setFontFeatureSettings("'tnum'");
        weatherPaint = paint(Ui.regular, 30, 0xD9FFFFFF);
        labelPaint = paint(Ui.medium, 26, WHITE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint = paint(Ui.medium, 22, WHITE);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        chipPaint = paint(Ui.medium, 26, WHITE);
        sectionPaint = paint(Ui.semibold, 26, 0xB3FFFFFF);
        cardTitlePaint = paint(Ui.semibold, 26, WHITE);
        cardSubPaint = paint(Ui.regular, 22, 0x99FFFFFF);
        npTitlePaint = paint(Ui.semibold, 28, WHITE);
        npArtistPaint = paint(Ui.regular, 24, 0xB3FFFFFF);
        npSmallPaint = paint(Ui.semibold, 18, 0x99FFFFFF);
        npSmallPaint.setLetterSpacing(0.08f);
        toastPaint = paint(Ui.medium, 26, WHITE);
        gridLabelPaint = paint(Ui.regular, 22, 0xCCFFFFFF);
        gridLabelPaint.setTextAlign(Paint.Align.CENTER);
        sheetTitlePaint = paint(Ui.semibold, 44, WHITE);
        for (String s : new String[] {"live_tv", "tv_settings", "settings", "tv", "hdmi", "gamepad", "play", "speaker",
                "chevron_down", "bell", "apps"}) {
            Symbols.warm(getContext(), s, 28 * k);
            Symbols.warm(getContext(), s, 30 * k);
        }
        makeShadow();
    }

    private TextPaint paint(android.graphics.Typeface face, float size, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size * k);
        p.setColor(color);
        return p;
    }

    /** The soft shadow under a focused tile or card: a blurred rounded square, drawn once (blur is software only). */
    private void makeShadow() {
        int blur = Math.max(4, Math.round(26 * k)), size = Math.round(TILE * k);
        int side = size + 4 * blur;
        Bitmap b = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        new Canvas(b).drawRoundRect(2 * blur, 2 * blur, side - 2 * blur, side - 2 * blur, TILE_R * k, TILE_R * k, p);
        shadow = b;
        shadowPaint.setColor(0xFF000000);
    }

    void resume() {
        if (running) return;
        running = true;
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst: the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastFrame = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        recs.prefetchAll();
        Photos.importInbox(getContext(), () -> { if (running) nextBackground(false); });
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        if (connectivity != null) {
            try {
                connectivity.registerDefaultNetworkCallback(network, handler);
            } catch (RuntimeException ignored) {
            }
        }
        updateNetwork();
        nowPlaying.start();
        onNowPlaying();
        handler.postDelayed(tick, 15_000);
        handler.post(clockTick);
        handler.postDelayed(background, bg == null ? 0 : BG_EVERY);
        checkNotices();
        if (!again) reload();
        if (sheetOpen) closeSheet();
        sheet.snap(0);
        wake();
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        framing = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(background);
        handler.removeCallbacks(reloadSoon);
        handler.removeCallbacks(progressTick);
        data.removeListener(this);
        recs.removeListener(this);
        nowPlaying.stop();
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        if (connectivity != null) {
            try {
                connectivity.unregisterNetworkCallback(network);
            } catch (RuntimeException ignored) {
            }
        }
    }

    void destroy() {
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Home pressed on the home screen: back to the start (first app focused, sheet closed, scrolled to the top). */
    void reset(boolean animated) {
        cancelReorder(!animated);
        sheetOpen = false;
        zone = Z_SHELF;
        shelfIndex = 0;
        shelfFirst = 0;
        for (Row r : rows) r.index = r.first = 0;
        retarget();
        if (!animated) snapAll();
        wake();
        spoken.focusMoved();
    }

    private void snapAll() {
        page.snap(page.target);
        sheet.snap(sheet.target);
        shelfScroll.snap(shelfScroll.target);
        for (Spring s : tileFocus) s.snap(s.target);
        for (Row r : rows) r.scroll.snap(r.scroll.target);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            recs.prefetchAll();
            checkNotices();
            handler.postDelayed(this, 15_000);
        }
    };

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (updateClock()) invalidate();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    private final Runnable background = new Runnable() {
        @Override public void run() {
            if (!running) return;
            nextBackground(false);
            handler.postDelayed(this, BG_EVERY);
        }
    };

    /** While a track with a known length plays: its progress bar moves once a second. */
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (!running || track == null || track.duration <= 0) return;
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

    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { handler.post(HomeView.this::updateNetwork); }
        @Override public void onLost(Network n) { handler.post(HomeView.this::updateNetwork); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) { handler.post(HomeView.this::updateNetwork); }
    };

    /** Apps installed, removed or updated: their tiles are made again and the lists read again. */
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
        if (running) reloadRows();
    }

    @Override public void onNowPlaying() {
        NowPlaying.Track t = nowPlaying.track();
        boolean had = track != null;
        track = t;
        npShow.target = t != null ? 1 : 0;
        if (t == null && zone == Z_INPUTS && inputIndex >= inputs.size()) {
            inputIndex = Math.max(0, inputs.size() - 1);
            if (inputs.isEmpty()) zone = Z_SHELF;
            retarget();
        }
        if (t != null && t.art != npArtBitmap) {
            npArtBitmap = t.art;
            npArt = t.art == null ? null : new BitmapShader(t.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        updateTrackText();
        handler.removeCallbacks(progressTick);
        if (t != null && t.duration > 0 && running) handler.postDelayed(progressTick, 1000);
        if (had != (t != null)) layoutChips();
        wake();
    }

    private void updateTrackText() {
        if (npTitlePaint == null || track == null) return;
        float textW = (NP_W - NP_H - 50) * k;
        npTitle = fit(track.title, npTitlePaint, textW);
        npArtist = fit(track.artist, npArtistPaint, textW);
        String label = Ui.tr("Now playing").toUpperCase(Ui.locale());
        if (!track.app.isEmpty() && !track.app.equals(track.title)) label += " · " + track.app.toUpperCase(Ui.locale());
        npLabel = fit(label, npSmallPaint, textW);
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

    private int clockGeneration = -1;

    private void updateWeather() {
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) {
            weatherText = "";
            weatherSymbol = null;
            return;
        }
        weatherSymbol = Symbols.weather(wx.code, wx.isDay);
        String city = Prefs.city();
        weatherText = HomeData.degrees(wx.temp) + "  ·  " + HomeData.condition(wx.code, wx.isDay) + "  ·  "
                + HomeData.highLow(wx, "  ") + (city.isEmpty() ? "" : "  ·  " + city);
    }

    private void updateNetwork() {
        boolean on = false, isWifi = false;
        try {
            Network n = connectivity == null ? null : connectivity.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : connectivity.getNetworkCapabilities(n);
            if (caps != null) {
                on = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
        } catch (RuntimeException ignored) {
        }
        if (on != online || isWifi != wifi) {
            online = on;
            wifi = isWifi || !on;
            invalidate();
        }
    }

    // MARK: - Lists (in the background, so coming home is never held up)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration || up - appsAt > 10 * 60_000L;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> in = inputList(c);
            final List<WatchNext.Entry> next = WatchNext.load(c);
            handler.post(() -> {
                if (s != null && !reordering) setShelf(s);
                setInputs(in);
                watchNext = next;
                reloadRows();
                if (apps && sheetOpen) loadGrid();
            });
        });
    }

    private void setShelf(List<HomeApps.Item> apps) {
        List<HomeApps.Item> list = new ArrayList<>(apps);
        list.add(HomeApps.action(HomeApps.ALL_APPS, "all", Ui.tr("All Apps"), "apps"));
        // Tiles already made for the same app carry over (no gray flash while the new ones are drawn).
        Map<String, HomeApps.Item> had = new HashMap<>();
        for (HomeApps.Item it : shelf) if (it.square != null) had.put(squareKey(it, tilePx()), it);
        for (HomeApps.Item it : list) {
            HomeApps.Item old = had.get(squareKey(it, tilePx()));
            if (old != null) {
                it.square = old.square;
                it.squareShader = old.squareShader;
            }
        }
        Spring[] focus = new Spring[list.size()], slide = new Spring[list.size()];
        for (int i = 0; i < list.size(); i++) {
            focus[i] = i < tileFocus.length ? tileFocus[i] : spring();
            slide[i] = i < tileSlide.length ? tileSlide[i] : new Spring(0.32f, 0.9f, 0);
        }
        shelf = list;
        tileFocus = focus;
        tileSlide = slide;
        fitShelfLabels();
        shelfIndex = Math.min(shelfIndex, list.size() - 1);
        loadSquares(list, tilePx());
        retarget();
        invalidate();
    }

    private void fitShelfLabels() {
        shelfLabels = new String[shelf.size()];
        for (int i = 0; i < shelf.size(); i++) {
            String label = shelf.get(i).label;
            shelfLabels[i] = labelPaint == null ? label : fit(label, labelPaint, (TILE + TILE_GAP) * k * 1.6f);
        }
    }

    /** Live TV and the HDMI inputs, then the maker's own screens where the TV has them (HY300: Keystone, Screen Mirroring). */
    private static List<HomeApps.Item> inputList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        for (HomeApps.Item it : HomeApps.drawerShortcuts(c)) if (it.kind == HomeApps.DEVICE) list.add(it);
        return list;
    }

    private void setInputs(List<HomeApps.Item> list) {
        String focusedKey = zone == Z_INPUTS && inputIndex < inputs.size() ? inputs.get(inputIndex).key : null;
        Spring[] focus = new Spring[list.size()];
        for (int i = 0; i < list.size(); i++) focus[i] = i < chipFocus.length ? chipFocus[i] : spring();
        inputs = list;
        chipFocus = focus;
        if (focusedKey != null) {
            for (int i = 0; i < list.size(); i++) if (list.get(i).key.equals(focusedKey)) inputIndex = i;
        }
        if (inputIndex > inputs.size() || (inputIndex == inputs.size() && track == null)) inputIndex = Math.max(0, inputs.size() - 1);
        if (zone == Z_INPUTS && inputItems() == 0) zone = Z_SHELF;
        layoutChips();
        retarget();
        invalidate();
    }

    /** The chips' places and texts (their widths follow their names). */
    private void layoutChips() {
        if (chipPaint == null) return;
        int n = inputs.size();
        chipX = new float[n];
        chipW = new float[n];
        chipText = new String[n];
        float x = M * k, pad = 30 * k, icon = 30 * k, gap = 14 * k;
        for (int i = 0; i < n; i++) {
            HomeApps.Item it = inputs.get(i);
            chipText[i] = fit(it.label, chipPaint, 280 * k);
            chipX[i] = x;
            chipW[i] = pad + icon + gap + chipPaint.measureText(chipText[i]) + pad;
            x += chipW[i] + 18 * k;
        }
        retarget();
    }

    private int inputItems() {
        return inputs.size() + (track != null ? 1 : 0);
    }

    /** The rows below: Continue watching, then movies and shows for you (the picks with an image on the TV). */
    private void reloadRows() {
        final List<WatchNext.Entry> entries = watchNext;
        final int max = HomeApps.lowRam(getContext()) ? 6 : 12;
        final String continueTitle = Ui.tr("Continue watching"), moviesTitle = Ui.tr("Movies"), showsTitle = Ui.tr("Shows");
        LOADER.execute(() -> {
            List<Row> out = buildRows(entries, max, continueTitle, moviesTitle, showsTitle);
            handler.post(() -> setRows(out));
        });
    }

    private List<Row> buildRows(List<WatchNext.Entry> watchNext, int max, String continueTitle, String moviesTitle, String showsTitle) {
        List<Row> out = new ArrayList<>();
        List<Card> continueCards = new ArrayList<>();
        for (WatchNext.Entry e : watchNext) {
            Card c = new Card();
            c.title = e.title;
            c.subtitle = e.subtitle;
            c.image = e.image;
            c.intent = e.intent;
            c.pkg = e.pkg;
            c.progress = e.progress;
            continueCards.add(c);
        }
        if (!continueCards.isEmpty()) out.add(new Row(continueTitle, continueCards));
        List<Card> movies = new ArrayList<>(), shows = new ArrayList<>();
        for (Recs.Pick p : recs.showcase()) {
            File f = recs.readyImage(p);
            if (f == null || p.pkg == null) continue;
            List<Card> list = "tv".equals(p.kind) ? shows : movies;
            if (list.size() >= max) continue;
            Card c = new Card();
            c.title = p.title == null ? "" : p.title;
            String service = Recs.serviceName(p);
            c.subtitle = p.meta == null || p.meta.isEmpty() ? service : p.meta;
            c.file = f;
            c.pkg = p.pkg;
            c.uri = p.link;
            list.add(c);
        }
        if (!movies.isEmpty()) out.add(new Row(moviesTitle, movies));
        if (!shows.isEmpty()) out.add(new Row(showsTitle, shows));
        return out;
    }

    private void setRows(List<Row> out) {
        // The same titles keep their images (and the focus its place) when nothing changed.
        Set<String> keys = new HashSet<>();
        for (Row r : out) {
            for (Card c : r.cards) {
                String key = cardKey(c);
                keys.add(key);
                Card had = cardImages.get(key);
                if (had != null) {
                    c.bitmap = had.bitmap;
                    c.shader = had.shader;
                }
            }
        }
        cardImages.keySet().retainAll(keys);
        String focusedRow = zone == Z_ROWS && rowIndex < rows.size() ? rows.get(rowIndex).title : null;
        for (Row r : out) {
            for (Row old : rows) {
                if (old.title.equals(r.title)) {
                    r.index = Math.min(old.index, r.cards.size() - 1);
                    r.first = Math.min(old.first, Math.max(0, r.cards.size() - ROW_SLOTS));
                    r.scroll.snap(old.scroll.value);
                }
            }
        }
        rows = out;
        if (zone == Z_ROWS) {
            int found = -1;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).title.equals(focusedRow)) found = i;
            if (found >= 0) rowIndex = found;
            else if (rows.isEmpty()) zone = inputItems() > 0 ? Z_INPUTS : Z_SHELF;
            else rowIndex = Math.min(rowIndex, rows.size() - 1);
        }
        refreshRowText();
        loadCards();
        retarget();
        invalidate();
    }

    private static String cardKey(Card c) {
        return c.pkg + "|" + c.title + "|" + c.subtitle + "|" + c.image + "|" + c.file;
    }

    private void refreshRowText() {
        if (cardTitlePaint == null) return;
        for (Row r : rows) {
            for (Card c : r.cards) {
                c.titleFit = fit(c.title, cardTitlePaint, CARD_W * k);
                c.subtitleFit = fit(c.subtitle, cardSubPaint, CARD_W * k);
            }
        }
    }

    /** The cards' images, at the focused card's size, one at a time in the background. */
    private void loadCards() {
        if (w == 0) return;
        final int cw = Math.round(CARD_W * k * 1.06f), ch = Math.round(CARD_H * k * 1.06f);
        final Context c = getContext().getApplicationContext();
        for (Row r : rows) {
            for (Card card : r.cards) {
                final String key = cardKey(card);
                if (card.bitmap != null || !cardsPending.add(key)) continue;
                final File file = card.file;
                final String uri = card.image;
                IMAGES.execute(() -> {
                    Bitmap b = file != null ? decodeFile(file, cw, ch, false) : WatchNext.image(c, uri, cw, ch);
                    final Card ready = new Card();
                    if (b != null) {
                        ready.bitmap = b;
                        ready.shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    }
                    handler.post(() -> {
                        cardsPending.remove(key);
                        if (ready.bitmap == null) return;
                        cardImages.put(key, ready);
                        for (Row row : rows) {
                            for (Card now : row.cards) {
                                if (now.bitmap == null && key.equals(cardKey(now))) {
                                    now.bitmap = ready.bitmap;
                                    now.shader = ready.shader;
                                }
                            }
                        }
                        invalidate();
                    });
                });
            }
        }
    }

    // MARK: - Square tiles

    private int tilePx() {
        return Math.max(1, Math.round(TILE * k * (1 + GROW)));
    }

    private int gridPx() {
        return Math.max(1, Math.round(GRID_TILE * k * (1 + GROW)));
    }

    private static String squareKey(HomeApps.Item it, int px) {
        return (it.pkg != null ? it.pkg : it.key) + "@" + px;
    }

    /** Makes the missing square tiles in the background, posting each as it's ready. */
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

    /** Loads the next photo (or color) in the background; it fades in over the current one. */
    private void nextBackground(boolean first) {
        if (w == 0 || bgLoading) return;
        bgLoading = true;
        final int gen = ++bgGeneration;
        final Context c = getContext().getApplicationContext();
        final int bw = Math.min(w, Backdrop.W), bh = Math.round(bw * h / (float) w);
        final boolean own = Prefs.photos();
        final int index = bgNext++;
        IMAGES.execute(() -> {
            List<File> sources = new ArrayList<>();
            if (own) sources.addAll(Photos.files(c));
            if (sources.isEmpty()) {
                for (Recs.Pick p : recs.showcase()) {
                    File f = recs.readyImage(p);
                    if (f != null) sources.add(f);
                }
            }
            Bitmap b = null;
            if (!sources.isEmpty()) b = decodeFile(sources.get(Math.floorMod(index, sources.size())), bw, bh, true);
            if (b == null) b = wallpaper(index, bw, bh);
            final Bitmap ready = b;
            handler.post(() -> {
                bgLoading = false;
                if (gen != bgGeneration) return;
                if (bg == null || first) {
                    bg = ready;
                    bgIncoming = null;
                } else {
                    if (bgIncoming != null) bg = bgIncoming;
                    bgIncoming = ready;
                    bgFadeAt = SystemClock.uptimeMillis();
                }
                wake();
            });
        });
    }

    /**
     * A photo cropped to w × h. As a background (`scrims`), darkened where the text sits (baked in once, so drawing it is
     * one texture) and kept in GPU memory.
     */
    private static Bitmap decodeFile(File f, int w, int h, boolean scrims) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) return null;
            b = WatchNext.crop(b, w, h);
            if (!scrims) return b;
            if (!b.isMutable()) {
                Bitmap m = b.copy(Bitmap.Config.ARGB_8888, true);
                b.recycle();
                b = m;
            }
            darken(new Canvas(b), w, h);
            return gpu(b);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("background: " + e.getMessage());
            return null;
        }
    }

    /** The scrims: a little at the top, more at the bottom (the shelf and the inputs), and behind the clock. */
    private static void darken(Canvas c, int w, int h) {
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, new int[] {0x80000000, 0x1A000000, 0x40000000, 0xE6000000},
                new float[] {0, 0.36f, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.17f, h * 0.27f, w * 0.5f, 0x80000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
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

    /** Without photos: soft glowing colors, one palette per turn (made small, scaled up smooth). */
    private static final int[][] PALETTES = {
        {0xFF0A1222, 0xFF1F4F86, 0xFF18707A, 0xFF3B2C70},
        {0xFF140C1E, 0xFF5E2B74, 0xFF2B3F80, 0xFF80395A},
        {0xFF0A1714, 0xFF1F5E4C, 0xFF2C5276, 0xFF4E6A2E},
        {0xFF170E0B, 0xFF7E3C1F, 0xFF5C1F40, 0xFF2B3B70},
        {0xFF0C0F18, 0xFF34507A, 0xFF5A4A86, 0xFF1F6A6A},
    };

    private static Bitmap wallpaper(int index, int w, int h) {
        int[] pal = PALETTES[Math.floorMod(index, PALETTES.length)];
        int sw = 384, sh = Math.max(1, Math.round(384f * h / w));
        Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        c.drawColor(pal[0]);
        Paint p = new Paint(Paint.DITHER_FLAG);
        float[][] blobs = {{0.78f, 0.18f, 0.62f}, {0.12f, 0.92f, 0.7f}, {0.55f, 0.75f, 0.5f}};
        for (int i = 0; i < 3; i++) {
            float[] b = blobs[(i + index) % 3];
            p.setShader(new RadialGradient(b[0] * sw, b[1] * sh, b[2] * sw, Ui.alpha(pal[i + 1], 0.85f), Ui.alpha(pal[i + 1], 0), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, sw, sh, p);
        }
        darken(c, sw, sh);
        Bitmap big = Bitmap.createScaledBitmap(small, w, h, true);
        small.recycle();
        return gpu(big);
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
        if (bgIncoming != null && up - bgFadeAt >= BG_FADE) {
            bg = bgIncoming;
            bgIncoming = null;
        }
        moving |= bgIncoming != null;
        moving |= tickToast(up);
        invalidate();
        if (moving) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            framing = false;
        }
    }

    private boolean step(float dt) {
        boolean m = false;
        for (Spring s : topFocus) m |= s.step(dt);
        for (Spring s : tileFocus) m |= s.step(dt);
        for (Spring s : tileSlide) m |= s.step(dt);
        for (Spring s : chipFocus) m |= s.step(dt);
        for (Spring s : gridFocus) m |= s.step(dt);
        for (Row r : rows) {
            for (Spring s : r.focus) m |= s.step(dt);
            m |= r.scroll.step(dt);
        }
        m |= shelfScroll.step(dt);
        m |= chipScroll.step(dt);
        m |= npShow.step(dt);
        m |= npFocus.step(dt);
        m |= page.step(dt);
        m |= sheet.step(dt);
        m |= gridScroll.step(dt);
        m |= lift.step(dt);
        return m;
    }

    /** Points every focus spring and scroll at the current focus. */
    private void retarget() {
        for (int i = 0; i < topFocus.length; i++) topFocus[i].target = !sheetOpen && zone == Z_TOP && topIndex == i ? 1 : 0;
        for (int i = 0; i < tileFocus.length; i++) tileFocus[i].target = !sheetOpen && zone == Z_SHELF && shelfIndex == i ? 1 : 0;
        for (int i = 0; i < chipFocus.length; i++) chipFocus[i].target = !sheetOpen && zone == Z_INPUTS && inputIndex == i ? 1 : 0;
        npFocus.target = !sheetOpen && zone == Z_INPUTS && inputIndex == inputs.size() && track != null ? 1 : 0;
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            for (int i = 0; i < row.focus.length; i++) row.focus[i].target = !sheetOpen && zone == Z_ROWS && rowIndex == r && row.index == i ? 1 : 0;
            if (row.index < row.first) row.first = row.index;
            if (row.index > row.first + ROW_SLOTS - 1) row.first = row.index - ROW_SLOTS + 1;
            row.scroll.target = row.first * (CARD_W + CARD_GAP) * k;
        }
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i].target = sheetOpen && gridIndex == i ? 1 : 0;
        // The shelf scrolls when it holds more than it shows.
        if (shelfIndex < shelfFirst) shelfFirst = shelfIndex;
        if (shelfIndex > shelfFirst + SHELF_SLOTS - 1) shelfFirst = shelfIndex - SHELF_SLOTS + 1;
        shelfScroll.target = shelfFirst * (TILE + TILE_GAP) * k;
        // The chips scroll when they reach the now playing card.
        float limit = chipsRight();
        if (zone == Z_INPUTS && inputIndex < inputs.size() && inputIndex < chipX.length) {
            float left = chipX[inputIndex] - chipScroll.target, right = left + chipW[inputIndex];
            if (right > limit) chipScroll.target += right - limit;
            if (left < M * k) chipScroll.target -= M * k - left;
        }
        if (zone != Z_INPUTS && chipScroll.target != 0 && (chipX.length == 0 || chipX[chipX.length - 1] + chipW[chipW.length - 1] <= limit)) chipScroll.target = 0;
        page.target = zone == Z_ROWS && rowIndex < rows.size() ? (ROWS_TOP + rowIndex * ROW_H - 150) * k : 0;
        int row = gridIndex / COLS;
        if (row < gridFirstRow) gridFirstRow = row;
        if (row > gridFirstRow + GRID_VISIBLE_ROWS - 1) gridFirstRow = row - GRID_VISIBLE_ROWS + 1;
        gridScroll.target = gridFirstRow * GRID_ROW * k;
        sheet.target = sheetOpen ? 1 : 0;
        lift.target = reordering ? 1 : 0;
        wake();
    }

    private float chipsRight() {
        return track != null ? (RIGHT - NP_W - 40) * k : RIGHT * k;
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (w == 0 || clockPaint == null) return;
        drawBackground(c);
        float scroll = page.value, gone = Ui.clamp(scroll / (500 * k));
        if (gone > 0) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, 0.6f * gone));
            c.drawRect(0, 0, w, h, fill);
        }
        float s = sheet.value;
        if (s < 0.999f) {
            int save = c.save();
            c.translate(0, -scroll);
            if (gone < 1) {
                drawTop(c, 1 - gone);
                drawClock(c, 1 - gone);
            }
            if (scroll < (SHELF_TOP + TILE + 120) * k) drawShelf(c);
            drawInputs(c);
            drawNowPlaying(c);
            drawRows(c, scroll);
            c.restoreToCount(save);
            if (!rows.isEmpty() && gone < 1) drawPeek(c, 1 - gone);
        }
        if (s > 0.001f) drawSheet(c, s);
        drawToast(c);
    }

    private void drawBackground(Canvas c) {
        if (bg == null) {
            c.drawColor(0xFF0B0C10);
        } else {
            dst.set(0, 0, w, h);
            photo.setAlpha(255);
            c.drawBitmap(bg, null, dst, photo);
        }
        if (bgIncoming != null) {
            float t = Ui.clamp((SystemClock.uptimeMillis() - bgFadeAt) / (float) BG_FADE);
            t = t * t * (3 - 2 * t);
            dst.set(0, 0, w, h);
            photo.setAlpha(Math.round(255 * t));
            c.drawBitmap(bgIncoming, null, dst, photo);
            photo.setAlpha(255);
        }
    }

    private void drawClock(Canvas c, float alpha) {
        float x = M * k;
        textAt(c, dateText, x, DATE_BASE * k, datePaint, alpha);
        textAt(c, clockText, x - Ui.leftBearing(clockPaint, clockText), CLOCK_BASE * k, clockPaint, alpha);
        if (!weatherText.isEmpty()) {
            float size = 34 * k, base = WEATHER_BASE * k, sw = 0;
            if (weatherSymbol != null) {
                sw = Symbols.width(getContext(), weatherSymbol, size);
                Symbols.draw(getContext(), c, weatherSymbol, x + sw / 2, base - Ui.halfCap(weatherPaint), size, Ui.alpha(0xFFFFFFFF, 0.9f * alpha));
            }
            textAt(c, weatherText, x + sw + 14 * k, base, weatherPaint, alpha);
        }
    }

    /** Text at `alpha` of its paint's own opacity. */
    private static void textAt(Canvas c, String s, float x, float y, Paint p, float alpha) {
        int color = p.getColor();
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
        p.setColor(color);
    }

    /** Round buttons at the top right: translucent, white when focused (its name shows below). */
    private void drawTop(Canvas c, float alpha) {
        int n = topKeys.size();
        float d = TOP_D * k, gap = TOP_GAP * k, x = RIGHT * k - n * d - (n - 1) * gap, cy = TOP_CY * k;
        for (int i = 0; i < n; i++) {
            float f = Ui.clamp(topFocus[i].value), grow = 1 + 0.1f * topFocus[i].value;
            float cx = x + d / 2, r = d / 2 * grow;
            topRects[i].set(cx - r, cy - r, cx + r, cy + r);
            fill.setShader(null);
            fill.setColor(blend(0x29FFFFFF, WHITE, f));
            fill.setAlpha(Math.round(fill.getAlpha() * alpha));
            c.drawCircle(cx, cy, r, fill);
            int ink = Ui.alpha(blend(WHITE, INK, f), alpha);
            int key = topKeys.get(i);
            if (key == TOP_WIFI) {
                drawNetwork(c, cx, cy + 4 * k, 15 * k * grow, ink);
            } else {
                Symbols.draw(getContext(), c, key == TOP_LIVE ? "live_tv" : "settings", cx, cy, 28 * k * grow, ink);
            }
            if (f > 0.01f) textAt(c, topLabel(key), cx, cy + r + 40 * k, tipPaint, f * alpha);
            x += d + gap;
        }
    }

    private String topLabel(int key) {
        return key == TOP_WIFI ? Ui.tr("Wi-Fi") : key == TOP_LIVE ? Ui.tr("Live TV") : Ui.tr("TV Settings");
    }

    /** The Wi-Fi fan (three arcs and a dot); without internet, the arcs dim; on a cable, a small plug-like square stack. */
    private void drawNetwork(Canvas c, float cx, float cy, float r, int color) {
        if (online && !wifi) {
            fill.setShader(null);
            fill.setColor(color);
            float s = r * 0.42f;
            rect.set(cx - s, cy - r * 1.05f, cx + s, cy - r * 1.05f + 2 * s);
            c.drawRoundRect(rect, s * 0.3f, s * 0.3f, fill);
            rect.set(cx - r, cy + r * 0.05f, cx - r + 2 * s, cy + r * 0.05f + 2 * s);
            c.drawRoundRect(rect, s * 0.3f, s * 0.3f, fill);
            rect.set(cx + r - 2 * s, cy + r * 0.05f, cx + r, cy + r * 0.05f + 2 * s);
            c.drawRoundRect(rect, s * 0.3f, s * 0.3f, fill);
            return;
        }
        arc.setStrokeWidth(r * 0.24f);
        for (int i = 1; i <= 3; i++) {
            float rr = r * (0.42f + 0.38f * (i - 1)) * 1.15f;
            oval.set(cx - rr, cy - rr, cx + rr, cy + rr);
            arc.setColor(online || i == 1 ? color : Ui.alpha(color, 0.35f * ((color >>> 24) / 255f)));
            c.drawArc(oval, 225, 90, false, arc);
        }
        fill.setShader(null);
        fill.setColor(color);
        c.drawCircle(cx, cy - r * 0.05f, r * 0.16f, fill);
    }

    private void drawShelf(Canvas c) {
        float size = TILE * k, step = (TILE + TILE_GAP) * k, top = SHELF_TOP * k, x0 = M * k - shelfScroll.value;
        int focused = -1;
        for (int i = 0; i < shelf.size(); i++) {
            float x = x0 + i * step + tileSlide[i].value;
            if (x > w || x + size < 0) continue;
            if (zone == Z_SHELF && i == shelfIndex) {
                focused = i;    // drawn last, above its neighbors
                continue;
            }
            drawTile(c, i, x, top, size);
        }
        if (focused >= 0) drawTile(c, focused, x0 + focused * step + tileSlide[focused].value, top, size);
        // The focused app's name under it.
        for (int i = 0; i < shelf.size(); i++) {
            float f = Ui.clamp(tileFocus[i].value);
            if (f < 0.01f) continue;
            float cx = x0 + i * step + tileSlide[i].value + size / 2;
            textAt(c, shelfLabels[i], cx, top + size * (1 + GROW / 2) + 52 * k, labelPaint, f);
        }
    }

    private void drawTile(Canvas c, int i, float x, float top, float size) {
        HomeApps.Item it = shelf.get(i);
        float v = tileFocus[i].value, f = Ui.clamp(v);
        boolean lifted = reordering && i == shelfIndex;
        float grow = 1 + GROW * v + (lifted ? 0.06f * lift.value : 0);
        float cx = x + size / 2, cy = top + size / 2 - (lifted ? 18 * k * lift.value : 0);
        float half = size * grow / 2;
        rect.set(cx - half, cy - half, cx + half, cy + half);
        if (f > 0.01f) drawShadow(c, rect, f);
        float radius = TILE_R * k * grow;
        drawSquare(c, it, rect, radius, 1);
        if (f > 0.01f) {
            ring.setColor(Ui.alpha(WHITE, f));
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

    private void drawShadow(Canvas c, RectF r, float f) {
        if (shadow == null) return;
        float pad = r.width() * (shadow.getWidth() / (float) Math.round(TILE * k) - 1) / 2;
        float drop = 16 * k;
        rect2.set(r.left - pad, r.top - pad + drop, r.right + pad, r.bottom + pad + drop);
        shadowPaint.setAlpha(Math.round(150 * f));
        c.drawBitmap(shadow, null, rect2, shadowPaint);
    }

    private final RectF rect2 = new RectF();

    private void drawInputs(Canvas c) {
        if (inputs.isEmpty()) return;
        textAt(c, Ui.tr("Inputs"), M * k, INPUTS_TITLE * k, sectionPaint, 1);
        float top = CHIPS_TOP * k, ch = CHIP_H * k, limit = chipsRight();
        int save = c.save();
        c.clipRect(0, top - 20 * k, limit + 12 * k, top + ch + 20 * k);
        for (int i = 0; i < inputs.size() && i < chipX.length; i++) {
            HomeApps.Item it = inputs.get(i);
            float v = chipFocus[i].value, f = Ui.clamp(v), grow = 1 + 0.06f * v;
            float x = chipX[i] - chipScroll.value, cw = chipW[i];
            if (x > limit || x + cw < 0) continue;
            float cy = top + ch / 2;
            int s2 = c.save();
            c.scale(grow, grow, x + cw / 2, cy);
            rect.set(x, top, x + cw, top + ch);
            fill.setShader(null);
            fill.setColor(blend(it.connected ? 0x2EFFFFFF : 0x17FFFFFF, WHITE, f));
            c.drawRoundRect(rect, ch / 2, ch / 2, fill);
            int ink = blend(it.connected ? WHITE : 0x8CFFFFFF, INK, f);
            String symbol = it.kind == HomeApps.LIVE_TV ? "tv" : it.symbol != null ? it.symbol : "hdmi";
            Symbols.draw(getContext(), c, symbol, x + 45 * k, cy, 28 * k, ink);
            chipPaint.setColor(ink);
            c.drawText(chipText[i], x + 74 * k, cy + Ui.halfCap(chipPaint), chipPaint);
            chipPaint.setColor(WHITE);
            c.restoreToCount(s2);
        }
        c.restoreToCount(save);
    }

    /** What's playing: cover, a small "NOW PLAYING · Spotify", the title and the artist, and the progress. */
    private void drawNowPlaying(Canvas c) {
        float show = npShow.value;
        if (show < 0.01f) return;
        float a = Ui.clamp(show), v = npFocus.value, f = Ui.clamp(v), grow = 1 + 0.04f * v;
        float cw = NP_W * k, ch = NP_H * k, left = (RIGHT - NP_W) * k, top = NP_TOP * k + (1 - a) * 24 * k;
        float cy = top + ch / 2;
        rect.set(left, top, left + cw, top + ch);
        int save = a < 0.999f ? c.saveLayerAlpha(rect.left - 30 * k, rect.top - 30 * k, rect.right + 30 * k, rect.bottom + 30 * k, Math.round(255 * a))
                : c.save();
        c.scale(grow, grow, rect.centerX(), cy);
        fill.setShader(null);
        fill.setColor(blend(0x99141418, 0xD924242A, f));
        float radius = 30 * k;
        c.drawRoundRect(rect, radius, radius, fill);
        if (f > 0.01f) {
            ring.setColor(Ui.alpha(WHITE, f));
            ring.setStrokeWidth(4 * k);
            c.drawRoundRect(rect, radius, radius, ring);
        }
        float pad = (NP_H - NP_ART) / 2 * k, art = NP_ART * k;
        RectF ar = rect2;
        ar.set(left + pad, cy - art / 2, left + pad + art, cy + art / 2);
        if (npArt != null && npArtBitmap != null && !npArtBitmap.isRecycled()) {
            // Covers can be any shape: cropped to a square in the middle.
            float bw = npArtBitmap.getWidth(), bh = npArtBitmap.getHeight(), side = Math.min(bw, bh);
            src.set((bw - side) / 2, (bh - side) / 2, (bw + side) / 2, (bh + side) / 2);
            matrix.setRectToRect(src, ar, Matrix.ScaleToFit.FILL);
            npArt.setLocalMatrix(matrix);
            image.setShader(npArt);
            image.setAlpha(255);
            c.drawRoundRect(ar, 18 * k, 18 * k, image);
            image.setShader(null);
        } else {
            fill.setColor(0xFF2E2F36);
            c.drawRoundRect(ar, 18 * k, 18 * k, fill);
            Symbols.draw(getContext(), c, "play", ar.centerX(), ar.centerY(), 34 * k, 0xCCFFFFFF);
        }
        float tx = ar.right + 22 * k;
        c.drawText(npLabel, tx, cy - 30 * k, npSmallPaint);
        c.drawText(npTitle, tx, cy + 6 * k, npTitlePaint);
        c.drawText(npArtist, tx, cy + 40 * k, npArtistPaint);
        if (track != null && track.duration > 0) {
            float barW = left + cw - 28 * k - tx, by = cy + 54 * k;
            float p = Ui.clamp(track.positionNow() / (float) track.duration);
            fill.setColor(0x33FFFFFF);
            rect.set(tx, by, tx + barW, by + 4 * k);
            c.drawRoundRect(rect, 2 * k, 2 * k, fill);
            fill.setColor(WHITE);
            rect.right = tx + barW * p;
            c.drawRoundRect(rect, 2 * k, 2 * k, fill);
        }
        c.restoreToCount(save);
    }

    /** At the bottom of the first screen: the first row's name and an arrow, so you know there's more below. */
    private void drawPeek(Canvas c, float alpha) {
        String title = rows.get(0).title;
        float y = PEEK_BASE * k;
        textAt(c, title, w / 2f, y, tipPaint, 0.75f * alpha);
        Symbols.draw(getContext(), c, "chevron_down", w / 2f, y + 26 * k, 28 * k, Ui.alpha(WHITE, 0.6f * alpha));
    }

    private void drawRows(Canvas c, float scroll) {
        float cw = CARD_W * k, chh = CARD_H * k, step = (CARD_W + CARD_GAP) * k;
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            float top = (ROWS_TOP + r * ROW_H) * k;
            if (top - scroll > h || top + ROW_H * k - scroll < 0) continue;
            textAt(c, row.title, M * k, top + 36 * k, sectionPaint, 1);
            float cardsTop = top + 64 * k, x0 = M * k - row.scroll.value;
            int focused = -1;
            for (int i = 0; i < row.cards.size(); i++) {
                float x = x0 + i * step;
                if (x > w || x + cw < 0) continue;
                if (zone == Z_ROWS && rowIndex == r && row.index == i) {
                    focused = i;
                    continue;
                }
                drawCard(c, row, i, x, cardsTop, cw, chh);
            }
            if (focused >= 0) drawCard(c, row, focused, x0 + focused * step, cardsTop, cw, chh);
        }
    }

    private void drawCard(Canvas c, Row row, int i, float x, float top, float cw, float chh) {
        Card card = row.cards.get(i);
        float v = row.focus[i].value, f = Ui.clamp(v), grow = 1 + 0.06f * v;
        float cx = x + cw / 2, cy = top + chh / 2;
        rect.set(cx - cw * grow / 2, cy - chh * grow / 2, cx + cw * grow / 2, cy + chh * grow / 2);
        if (f > 0.01f) drawShadow(c, rect, f);
        float radius = CARD_R * k * grow;
        if (card.shader != null) {
            src.set(0, 0, card.bitmap.getWidth(), card.bitmap.getHeight());
            matrix.setRectToRect(src, rect, Matrix.ScaleToFit.FILL);
            card.shader.setLocalMatrix(matrix);
            image.setShader(card.shader);
            image.setAlpha(255);
            c.drawRoundRect(rect, radius, radius, image);
            image.setShader(null);
        } else {
            fill.setShader(null);
            fill.setColor(0xFF24252B);
            c.drawRoundRect(rect, radius, radius, fill);
        }
        if (card.progress >= 0) {
            float pad = 16 * k * grow, by = rect.bottom - pad - 6 * k;
            fill.setShader(null);
            fill.setColor(0x66000000);
            rect2.set(rect.left + pad, by, rect.right - pad, by + 6 * k);
            c.drawRoundRect(rect2, 3 * k, 3 * k, fill);
            fill.setColor(WHITE);
            rect2.right = rect2.left + (rect.width() - 2 * pad) * card.progress;
            c.drawRoundRect(rect2, 3 * k, 3 * k, fill);
        }
        if (f > 0.01f) {
            ring.setColor(Ui.alpha(WHITE, f));
            ring.setStrokeWidth(4 * k);
            float o = 7 * k;
            rect.inset(-o, -o);
            c.drawRoundRect(rect, radius + o, radius + o, ring);
        }
        float textTop = top + chh + 6 * k * v;
        if (card.titleFit != null) c.drawText(card.titleFit, x, textTop + 44 * k, cardTitlePaint);
        if (card.subtitleFit != null) c.drawText(card.subtitleFit, x, textTop + 78 * k, cardSubPaint);
    }

    /** All Apps: the apps in a grid over the darkened background. */
    private void drawSheet(Canvas c, float s) {
        float a = Ui.clamp(s);
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xF20A0A0D, a));
        c.drawRect(0, 0, w, h, fill);
        int save = a < 0.999f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * a)) : c.save();
        c.translate(0, (1 - a) * 40 * k);
        c.drawText(Ui.tr("All Apps"), M * k, 120 * k, sheetTitlePaint);
        float size = GRID_TILE * k, left = (w - ((COLS - 1) * GRID_STEP_X * k + size)) / 2f;
        int clip = c.save();
        c.clipRect(0, (GRID_TOP - 40) * k, w, h);
        for (int i = 0; i < grid.size(); i++) {
            int col = i % COLS, row = i / COLS;
            float x = left + col * GRID_STEP_X * k, top = (GRID_TOP + row * GRID_ROW) * k - gridScroll.value;
            if (top > h || top + GRID_ROW * k < 0) continue;
            float v = gridFocus[i].value, f = Ui.clamp(v), grow = 1 + GROW * v;
            float cx = x + size / 2, cy = top + size / 2, half = size * grow / 2;
            rect.set(cx - half, cy - half, cx + half, cy + half);
            if (f > 0.01f) drawShadow(c, rect, f);
            float radius = TILE_R * (GRID_TILE / TILE) * k * grow;
            drawSquare(c, grid.get(i), rect, radius, 1);
            if (f > 0.01f) {
                ring.setColor(Ui.alpha(WHITE, f));
                ring.setStrokeWidth(4 * k);
                float o = 7 * k;
                rect.inset(-o, -o);
                c.drawRoundRect(rect, radius + o, radius + o, ring);
            }
            gridLabelPaint.setColor(blend(0xB3FFFFFF, WHITE, f));
            c.drawText(gridLabels[i], cx, top + size + 44 * k + 6 * k * v, gridLabelPaint);
        }
        c.restoreToCount(clip);
        c.restoreToCount(save);
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

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

    private final Runnable wakeUp = this::wake;

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 30 * k, padY = 20 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float top = 44 * k - (1 - a) * 16 * k;
        rect.set((w - tw) / 2f - padX, top, (w + tw) / 2f + padX, top + th + 2 * padY);
        int save = c.saveLayerAlpha(rect.left - 4, rect.top - 4, rect.right + 4, rect.bottom + 4, Math.round(255 * a));
        fill.setShader(null);
        fill.setColor(0xF01C1C21);
        c.drawRoundRect(rect, Math.min(rect.height() / 2, 34 * k), Math.min(rect.height() / 2, 34 * k), fill);
        Symbols.draw(getContext(), c, "bell", rect.left + padX + iconW / 2, rect.centerY(), icon, 0xEBFFFFFF);
        c.translate(rect.left + padX + iconW + 16 * k, rect.top + padY);
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
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
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
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here. Held on a shelf app: move mode; on an app in
                // All Apps: on or off the shelf.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder();
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
        if (sheetOpen) {
            closeSheet();
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            focusChanged();
        } else if (shelfIndex != 0) {
            shelfIndex = 0;
            focusChanged();
        }
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        int before = zone * 10_000 + topIndex * 1000 + shelfIndex * 100 + inputIndex * 10 + rowIndex;
        int beforeCard = zone == Z_ROWS && rowIndex < rows.size() ? rows.get(rowIndex).index : -1;
        switch (zone) {
            case Z_TOP:
                if (dx != 0) topIndex = Math.max(0, Math.min(topKeys.size() - 1, topIndex + dx));
                if (dy > 0) zone = Z_SHELF;
                break;
            case Z_SHELF:
                if (dx != 0) shelfIndex = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = topKeys.size() - 1;
                } else if (dy > 0) {
                    if (inputItems() > 0) {
                        zone = Z_INPUTS;
                        inputIndex = nearestInput(tileCenter(shelfIndex));
                    } else if (!rows.isEmpty()) {
                        zone = Z_ROWS;
                        rowIndex = 0;
                    }
                }
                break;
            case Z_INPUTS:
                if (dx != 0) inputIndex = Math.max(0, Math.min(inputItems() - 1, inputIndex + dx));
                if (dy < 0) {
                    zone = Z_SHELF;
                    shelfIndex = nearestTile(inputIndex < inputs.size() && inputIndex < chipX.length
                            ? chipX[inputIndex] - chipScroll.value + chipW[inputIndex] / 2 : (RIGHT - NP_W / 2) * k);
                } else if (dy > 0 && !rows.isEmpty()) {
                    zone = Z_ROWS;
                    rowIndex = 0;
                }
                break;
            case Z_ROWS:
                Row row = rows.get(rowIndex);
                if (dx != 0) row.index = Math.max(0, Math.min(row.cards.size() - 1, row.index + dx));
                if (dy > 0 && rowIndex < rows.size() - 1) rowIndex++;
                if (dy < 0) {
                    if (rowIndex > 0) rowIndex--;
                    else zone = inputItems() > 0 ? Z_INPUTS : Z_SHELF;
                }
                break;
            default:
                break;
        }
        int after = zone * 10_000 + topIndex * 1000 + shelfIndex * 100 + inputIndex * 10 + rowIndex;
        int afterCard = zone == Z_ROWS && rowIndex < rows.size() ? rows.get(rowIndex).index : -1;
        if (after != before || afterCard != beforeCard) focusChanged();
    }

    private void focusChanged() {
        click.move();
        retarget();
        spoken.focusMoved();
    }

    private float tileCenter(int i) {
        return M * k - shelfScroll.target + i * (TILE + TILE_GAP) * k + TILE * k / 2;
    }

    private int nearestTile(float x) {
        int best = 0;
        float d = Float.MAX_VALUE;
        for (int i = 0; i < shelf.size(); i++) {
            float dd = Math.abs(tileCenter(i) - x);
            if (dd < d) {
                d = dd;
                best = i;
            }
        }
        return best;
    }

    private int nearestInput(float x) {
        if (track != null && x > chipsRight()) return inputs.size();
        int best = inputs.isEmpty() ? 0 : -1;
        float d = Float.MAX_VALUE;
        for (int i = 0; i < inputs.size() && i < chipX.length; i++) {
            float cx = chipX[i] - chipScroll.value + chipW[i] / 2;
            if (cx > chipsRight()) continue;
            float dd = Math.abs(cx - x);
            if (dd < d) {
                d = dd;
                best = i;
            }
        }
        return best < 0 ? 0 : best;
    }

    private void moveGrid(int dx, int dy) {
        int before = gridIndex;
        if (dx != 0) {
            int col = gridIndex % COLS + dx;
            if (col >= 0 && col < COLS && gridIndex + dx < grid.size()) gridIndex += dx;
        }
        if (dy != 0) {
            int next = gridIndex + dy * COLS;
            if (next >= 0 && next < grid.size()) gridIndex = next;
            else if (dy > 0 && gridIndex / COLS < (grid.size() - 1) / COLS) gridIndex = grid.size() - 1;
        }
        if (gridIndex != before) focusChanged();
    }

    private void activate() {
        if (sheetOpen) {
            if (gridIndex < grid.size()) open(grid.get(gridIndex), rectOf(A11Y_GRID, gridIndex));
            return;
        }
        switch (zone) {
            case Z_TOP:
                activateTop(topKeys.get(topIndex));
                break;
            case Z_SHELF: {
                HomeApps.Item it = shelf.get(shelfIndex);
                if (it.kind == HomeApps.ALL_APPS) openSheet(); else open(it, rectOf(A11Y_SHELF, shelfIndex));
                break;
            }
            case Z_INPUTS:
                if (inputIndex < inputs.size()) {
                    open(inputs.get(inputIndex), rectOf(A11Y_INPUTS, inputIndex));
                } else if (track != null) {
                    click.open();
                    if (!NowPlaying.open(getContext(), track)) showToast(Ui.trf("Couldn't open {app}.", "app", track.app));
                }
                break;
            case Z_ROWS: {
                Row row = rows.get(rowIndex);
                openCard(row.cards.get(row.index), rectOf(A11Y_ROW0 + rowIndex, row.index));
                break;
            }
            default:
                break;
        }
    }

    private void activateTop(int key) {
        click.open();
        Intent intent;
        if (key == TOP_WIFI) {
            intent = HomeApps.wifiSettings();
        } else if (key == TOP_LIVE) {
            intent = HomeApps.liveTv(getContext());
        } else {
            intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
        }
        if (intent == null || !startSafely(intent)) {
            if (key == TOP_WIFI && startSafely(new Intent(android.provider.Settings.ACTION_SETTINGS))) return;
            showToast(key == TOP_SETTINGS ? Ui.tr("Couldn't open TV Settings") : Ui.trf("Couldn't open {app}.", "app", topLabel(key)));
        }
    }

    private boolean startSafely(Intent intent) {
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void open(HomeApps.Item it, RectF from) {
        click.open();
        int x = Math.round(from.left), y = Math.round(from.top);
        if (!HomeApps.open(activity, it, this, x, y, Math.round(from.width()), Math.round(from.height()))) {
            showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
        }
    }

    private void openCard(Card card, RectF from) {
        click.open();
        if (card.intent != null) {
            Intent i = new Intent(card.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (startSafely(i)) return;
        }
        HomeApps.Item app = HomeApps.app(card.pkg, card.title, card.uri);
        if (!HomeApps.open(activity, app, this, Math.round(from.left), Math.round(from.top), Math.round(from.width()), Math.round(from.height()))) {
            showToast(Ui.trf("Couldn't open {app}.", "app", card.title));
        }
    }

    // MARK: - All Apps

    private void openSheet() {
        click.open();
        sheetOpen = true;
        gridIndex = 0;
        gridFirstRow = 0;
        gridScroll.snap(0);
        loadGrid();
        retarget();
        spoken.focusMoved();
    }

    private void closeSheet() {
        sheetOpen = false;
        retarget();
        spoken.focusMoved();
    }

    private void loadGrid() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> list = HomeApps.allApps(c);
            handler.post(() -> setGrid(list));
        });
    }

    private void setGrid(List<HomeApps.Item> list) {
        String focusedPkg = gridIndex < grid.size() ? grid.get(gridIndex).key : null;
        Spring[] focus = new Spring[list.size()];
        for (int i = 0; i < list.size(); i++) focus[i] = i < gridFocus.length ? gridFocus[i] : spring();
        grid = list;
        gridFocus = focus;
        gridLabels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            gridLabels[i] = gridLabelPaint == null ? list.get(i).label : fit(list.get(i).label, gridLabelPaint, (GRID_STEP_X - 16) * k);
            if (list.get(i).key.equals(focusedPkg)) gridIndex = i;
        }
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        loadSquares(list, gridPx());
        retarget();
    }

    private void hold() {
        if (sheetOpen) toggleShelf(); else startReorder();
    }

    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i < 0 || i >= grid.size()) return false;
        HomeApps.Item it = grid.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
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
                reload();
            });
        });
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || appCount() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private int appCount() {
        int n = 0;
        for (HomeApps.Item it : shelf) if (it.kind == HomeApps.APP) n++;
        return n;
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
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
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

    /** Swaps the lifted app with its neighbor (never past All Apps); both slide to their new places. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size() || shelf.get(j).kind != HomeApps.APP) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        String l = shelfLabels[i];
        shelfLabels[i] = shelfLabels[j];
        shelfLabels[j] = l;
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = (i - j) * (TILE + TILE_GAP) * k;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        shelfIndex = j;
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
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows: the shown ones take their new order in the same
            // slots, the others stay where they were.
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
        // Everything slides back to where it was (or jumps, when leaving the home screen).
        float step = (TILE + TILE_GAP) * k;
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
        fitShelfLabels();
        shelfIndex = shelf.indexOf(lifted);
        retarget();
        spoken.focusMoved();
    }

    // MARK: - TalkBack (each item the remote can reach is a virtual node)

    private static final int A11Y_TOP = 1, A11Y_SHELF = 2, A11Y_INPUTS = 3, A11Y_GRID = 4, A11Y_ROW0 = 10;

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < grid.size(); i++) out.add(A11Y_GRID * 1000 + i + 1);
            return;
        }
        for (int i = 0; i < topKeys.size(); i++) out.add(A11Y_TOP * 1000 + i + 1);
        for (int i = 0; i < shelf.size(); i++) out.add(A11Y_SHELF * 1000 + i + 1);
        for (int i = 0; i < inputItems(); i++) out.add(A11Y_INPUTS * 1000 + i + 1);
        for (int r = 0; r < rows.size(); r++) {
            for (int i = 0; i < rows.get(r).cards.size(); i++) out.add((A11Y_ROW0 + r) * 1000 + i + 1);
        }
    }

    private String a11yText(int id) {
        int group = id / 1000, i = id % 1000 - 1;
        if (group == A11Y_TOP && i < topKeys.size()) return topLabel(topKeys.get(i));
        if (group == A11Y_SHELF && i < shelf.size()) {
            String label = shelf.get(i).label;
            return reordering && i == shelfIndex
                    ? Ui.trf("Moving {app}, position {n} of {total}", "app", label, "n", i + 1, "total", appCount()) : label;
        }
        if (group == A11Y_INPUTS) {
            if (i < inputs.size()) return inputs.get(i).label;
            if (track != null) return Ui.tr("Now playing") + ", " + track.title + (track.artist.isEmpty() ? "" : ", " + track.artist);
        }
        if (group == A11Y_GRID && i < grid.size()) return grid.get(i).label;
        int r = group - A11Y_ROW0;
        if (r >= 0 && r < rows.size() && i < rows.get(r).cards.size()) {
            Card c = rows.get(r).cards.get(i);
            return rows.get(r).title + ", " + c.title + (c.subtitle.isEmpty() ? "" : ", " + c.subtitle);
        }
        return "";
    }

    private boolean a11yBounds(int id, Rect out) {
        RectF r = rectOf(id / 1000, id % 1000 - 1);
        if (r == null || r.isEmpty()) return false;
        r.round(out);
        return true;
    }

    /** Where an item is on screen now (a new RectF: TalkBack and the opening animation keep it). */
    private RectF rectOf(int group, int i) {
        RectF r = new RectF();
        float scroll = page.value;
        if (group == A11Y_TOP && i < topRects.length) {
            r.set(topRects[i]);
            r.offset(0, -scroll);
        } else if (group == A11Y_SHELF) {
            float x = M * k - shelfScroll.value + i * (TILE + TILE_GAP) * k;
            r.set(x, SHELF_TOP * k - scroll, x + TILE * k, (SHELF_TOP + TILE) * k - scroll);
        } else if (group == A11Y_INPUTS) {
            if (i < inputs.size() && i < chipX.length) {
                float x = chipX[i] - chipScroll.value;
                r.set(x, CHIPS_TOP * k - scroll, x + chipW[i], (CHIPS_TOP + CHIP_H) * k - scroll);
            } else {
                r.set((RIGHT - NP_W) * k, NP_TOP * k - scroll, RIGHT * k, (NP_TOP + NP_H) * k - scroll);
            }
        } else if (group == A11Y_GRID) {
            float size = GRID_TILE * k, left = (w - ((COLS - 1) * GRID_STEP_X * k + size)) / 2f;
            float x = left + (i % COLS) * GRID_STEP_X * k, top = (GRID_TOP + (i / COLS) * GRID_ROW) * k - gridScroll.value;
            r.set(x, top, x + size, top + size);
        } else if (group >= A11Y_ROW0 && group - A11Y_ROW0 < rows.size()) {
            Row row = rows.get(group - A11Y_ROW0);
            float x = M * k - row.scroll.value + i * (CARD_W + CARD_GAP) * k;
            float top = (ROWS_TOP + (group - A11Y_ROW0) * ROW_H + 64) * k - scroll;
            r.set(x, top, x + CARD_W * k, top + CARD_H * k);
        }
        return r;
    }

    private int a11yFocused() {
        if (sheetOpen) return A11Y_GRID * 1000 + gridIndex + 1;
        switch (zone) {
            case Z_TOP: return A11Y_TOP * 1000 + topIndex + 1;
            case Z_INPUTS: return A11Y_INPUTS * 1000 + inputIndex + 1;
            case Z_ROWS: return rowIndex < rows.size() ? (A11Y_ROW0 + rowIndex) * 1000 + rows.get(rowIndex).index + 1 : -1;
            default: return A11Y_SHELF * 1000 + shelfIndex + 1;
        }
    }

    private void a11yFocus(int id) {
        int group = id / 1000, i = id % 1000 - 1;
        if (group == A11Y_GRID && sheetOpen && i < grid.size()) {
            gridIndex = i;
        } else if (group == A11Y_TOP && i < topKeys.size()) {
            zone = Z_TOP;
            topIndex = i;
        } else if (group == A11Y_SHELF && i < shelf.size()) {
            zone = Z_SHELF;
            shelfIndex = i;
        } else if (group == A11Y_INPUTS && i < inputItems()) {
            zone = Z_INPUTS;
            inputIndex = i;
        } else if (group >= A11Y_ROW0 && group - A11Y_ROW0 < rows.size()) {
            zone = Z_ROWS;
            rowIndex = group - A11Y_ROW0;
            rows.get(rowIndex).index = Math.min(i, rows.get(rowIndex).cards.size() - 1);
        } else {
            return;
        }
        retarget();
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
