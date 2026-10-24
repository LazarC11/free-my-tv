package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvTrackInfo;
import android.media.tv.TvView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the time and the weather now (or, while music plays, the player) and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: 5:3 icons (210 × 126) in each brand's color, 48 apart, and the glass bar hugging
    // them with 40 of padding (the focused icon grows 20% without touching its edge).
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22, TILE_MAX_W = 270;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static final String[] TABS = {"Explorar", "Filmes", "Séries"};
    private static final String[] TABS_RU = {"Обзор", "Фильмы", "Сериалы"};
    private static final String[] TABS_EN = {"Explore", "Movies", "Shows"};
    private static final String[] TABS_ES = {"Explorar", "Películas", "Series"};
    private static final String[] TABS_DE = {"Entdecken", "Filme", "Serien"};
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_PLAYER = 6, PLAYER_LAYER = 8, PANEL_LAYER = 9, INPUT_MASK = 10;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // Player (MediaNow), in place of the greeting while something plays or is paused: equalizer, song, ⏮ ⏯ ⏭
    private final MediaNow media;
    private final Layer playerLayer;
    private boolean playerOn, playerDirty;
    private int playerIndex = 1;
    private final Spring[] playerFocus = new Spring[3];
    private final RectF[] playerRects = new RectF[3];
    private Paint pTitle, pArtist;
    private final Path glyph = new Path();
    private long eqAt, eqDrawnAt, songAt;
    private final float[] eqShown = new float[4];
    private boolean eqSettled = true;
    private static final float EQ_REST = 0.18f;             // a resting bar, as a share of the tallest
    private static final long SONG_MS = 480;                // a new song's title coming in
    private final Spring playerTextW = new Spring(0.4f, 0.9f, 0);  // where the buttons go: after the text
    private String songFrom, artistFrom, shownTitle, shownArtist;
    private final Beat beat = new Beat(4);
    static final float[] EQ_SPEED = {5.1f, 7.3f, 4.2f, 6.4f}, EQ_PHASE = {0, 1.7f, 3.1f, 0.6f};
    private static final long EQ_MS = 33;   // the bars at ~30 fps: enough to follow the beat, without redrawing at 60

    // Liquid Glass panel (Settings > Liquid Glass opens it here): the glass, the background's brightness and its blur,
    // changed live on the home screen behind it
    private final Layer panelLayer;
    private boolean panelOpen;
    private int panelRow;
    private final Spring panel = new Spring(0.3f, 0.9f, 0);
    private final RectF panelRect = new RectF();
    private Paint panelTitle, panelLabel, panelValue, panelHint;

    // Explore: the music playing now (MediaNow, one square card that follows the song) and what you watched (NowPlaying,
    // NetflixHistory). Each card's extras hang on its pick.
    private static final String PLAYED = "played", MUSIC = "music";
    private List<Recs.Pick> played = new ArrayList<>();
    private Recs.Pick musicPick;
    // The drawer's focused HDMI input, if it sends a picture, plays (muted) in a TvView under the row: its video shows
    // through a hole in the layers below it, square, so a small layer above it draws the background back over its
    // corners
    // Resting on an input this long starts its picture (passing over the inputs doesn't switch each one on)
    private static final long PREVIEW_DELAY_MS = 300;
    private final Spring inputShow = new Spring(0.4f, 0.9f, 0);
    private final RectF inputPreview = new RectF();
    private HomeApps.Item previewItem;
    private long previewSince;
    private String signalId;        // the input whose picture plays (its first frame came)
    private boolean previewShown;   // in its place (else waiting in the corner)
    private int videoW, videoH;
    private float videoHz;
    private boolean videoAllm, videoInterlaced;
    private String videoHdr, signalText = "", portText = "";
    private boolean previewHeld;    // an input was opened: no picture until the home screen comes back
    private TvView preview;
    private Layer previewMask;
    private String previewTuned;
    private final Path previewCorners = new Path();
    private final Rect previewBox = new Rect();
    private static final class Watched {
        Backdrop backdrop;      // the card's picture and the background (a cover: on its blurred self)
        Bitmap cover;           // music: the square cover, drawn as is
        HomeApps.Item app;
        String video;
        long netflix;
    }
    private final Map<Recs.Pick, Watched> watchedOf = new java.util.IdentityHashMap<>();
    private static final Map<String, Backdrop> PLAYED_BACKDROPS = new java.util.LinkedHashMap<>();
    private int lastWide;       // with the music card selected (square, never wide), this card stays wide
    // The three tabs, then the round buttons at the right (the TV provider or Live TV, HDMI Inputs, Wi-Fi, Settings)
    private static final int TOP_SLOTS = 7;
    private final Spring[] topFocus = new Spring[TOP_SLOTS];
    private final RectF[] topRects = new RectF[TOP_SLOTS];
    // Up to ten cards (Movies, Shows) in a row that scrolls; Explore has three
    private static final int MAX_CARDS = Recs.MAX_CARDS;
    private final Spring[] cardFocus = new Spring[MAX_CARDS];
    private final RectF[] cardRects = new RectF[MAX_CARDS];
    private final Spring cardScroll = new Spring(0.42f, 0.9f, 0);
    // The selected card is the wide one (Apple TV app): each card's share of the extra width, animated
    private final Spring[] cardWide = new Spring[MAX_CARDS];
    private float cardsLeft, cardsTop, cardH, cardGap, tallW, wideW;
    private final Matrix unitMatrix = new Matrix();
    // Shortened texts kept per slot: the cards' titles and details, then the player's song and artist (and the previous
    // ones while they fade out)
    private static final int FIT_TITLE = 0, FIT_META = MAX_CARDS, FIT_PLAYER = 2 * MAX_CARDS, FIT_SLOTS = FIT_PLAYER + 4;
    private final String[] fitText = new String[FIT_SLOTS], fitSource = new String[FIT_SLOTS];
    private final int[] fitSteps = new int[FIT_SLOTS];
    private final float[] fitSize = new float[FIT_SLOTS];
    private LinearGradient lightSheen, lightRim;
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTitleDim, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) startReorder(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    // The same motion as the drawer's: the page scrolls on to the apps the way it scrolled down to the drawer
    private final Spring sheet = new Spring(0.46f, 0.92f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "", titleTemp = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);

    // The connection, a round button top right (next to HDMI Inputs): Wi-Fi wedges by signal, "!" without internet,
    // struck through offline, a socket for Ethernet; it opens the TV's Wi-Fi settings
    private static final int NET_OFF = 0, NET_NO_INTERNET = 1, NET_WIFI = 2, NET_WIRED = 3;
    private volatile int netState = NET_WIFI, netLevel = 3;
    private final Paint net = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF netArc = new RectF();
    private final Path netPath = new Path();
    private float netCorners;
    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) { netChanged(caps); }
        @Override public void onLost(Network n) { netChanged(null); }
    };
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case PLAYER_LAYER: drawPlayer(c); break;
                case PANEL_LAYER: drawGlassPanel(c); break;
                case INPUT_MASK: drawPreviewMask(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    private final Runnable cardBackdropLater = this::showCardBackdrop;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        playerLayer = new Layer(a, PLAYER_LAYER);
        panelLayer = new Layer(a, PANEL_LAYER);
        media = new MediaNow(a, this::mediaChanged);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 9 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, playerLayer, status, platter, tiles, drawerLayer, sheetLayer, panelLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, playerLayer, status, platter, tiles, drawerLayer, sheetLayer, panelLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < TOP_SLOTS; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < MAX_CARDS; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardWide[i] = new Spring(0.42f, 0.9f, i == 0 ? 1 : 0);
            cardRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            playerFocus[i] = new Spring(0.26f, 0.85f, 0);
            playerRects[i] = new RectF();
        }
        playerLayer.setVisibility(INVISIBLE);
        panelLayer.setVisibility(INVISIBLE);
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        previewHeld = false;
        checkNight(false);
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            netChanged(cm.getNetworkCapabilities(cm.getActiveNetwork()));
            cm.registerDefaultNetworkCallback(netCallback, handler);
        }
        media.start();
        handler.postDelayed(tick, 15_000);
        checkNotices();
        reload();
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        handler.removeCallbacks(cardBackdropLater);
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(netCallback);
            } catch (IllegalArgumentException ignored) {}  // not registered
        }
        media.stop();
        beat.stop();
        stopPreview();
        if (panelOpen) {
            // Leaving the home screen closes the panel (the settings are already saved)
            panelOpen = false;
            panel.snap(0);
            panelLayer.setVisibility(INVISIBLE);
        }
    }

    /** The connection changed (on the main thread): the icon follows. */
    private void netChanged(NetworkCapabilities caps) {
        int state, level = 3;
        if (caps == null) {
            state = NET_OFF;
        } else if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            state = NET_NO_INTERNET;    // connected, but the internet doesn't answer
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            state = NET_WIRED;
        } else {
            state = NET_WIFI;
        }
        if (caps != null && Build.VERSION.SDK_INT >= 29 && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            int rssi = caps.getSignalStrength();     // dBm; unknown on some TVs (then full)
            if (rssi != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) level = rssi >= -60 ? 3 : rssi >= -70 ? 2 : 1;
        }
        if (state == netState && level == netLevel) return;
        netState = state;
        netLevel = level;
        statusDirty = true;     // the Wi-Fi button, top right
        spoken.focusMoved();
        wake();
    }

    /** The connection tile's name: what the connection is now. */
    private String netLabel() {
        switch (netState) {
            case NET_OFF: return Ui.tr("Sem conexão", "Offline", "Sin conexión");
            case NET_NO_INTERNET: return Ui.tr("Sem internet", "No internet", "Sin internet");
            case NET_WIRED: return "Ethernet";
            default: return "Wi-Fi";
        }
    }

    // The Wi-Fi wedges, as shares of the icon's size: inner and outer radius of each (the first is a slice)
    private static final float[] WEDGE_IN = {0, 0.31f, 0.55f}, WEDGE_OUT = {0.22f, 0.46f, 0.7f};

    /**
     * The connection icon, `size` across, around (cx, cy), filled like a phone's: three Wi-Fi wedges (the ones beyond
     * the signal dimmed), "!" beside them without internet, all dimmed and struck through offline; a network socket for
     * Ethernet.
     */
    private void drawNetwork(Canvas c, float cx, float cy, float size, int color) {
        int state = netState, a = Color.alpha(color);
        // Soft corners, made again only when their whole-pixel radius changes (the focused button grows every frame)
        float corner = Math.max(1, Math.round(size * 0.06f));
        if (netCorners != corner) {
            netCorners = corner;
            net.setPathEffect(new android.graphics.CornerPathEffect(corner));
        }
        net.setColor(color);
        if (state == NET_WIRED) {
            // A network socket: a box and three pins
            net.setStyle(Paint.Style.STROKE);
            net.setStrokeWidth(size * 0.1f);
            float w2 = size * 0.4f, h2 = size * 0.32f;
            netArc.set(cx - w2, cy - h2, cx + w2, cy + h2);
            c.drawRoundRect(netArc, size * 0.08f, size * 0.08f, net);
            for (int i = -1; i <= 1; i++) c.drawLine(cx + i * size * 0.18f, cy - h2 * 0.2f, cx + i * size * 0.18f, cy + h2 * 0.45f, net);
            return;
        }
        float apexY = cy + size * 0.35f;     // where the wedges meet, at the bottom
        net.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {
            boolean lit = state != NET_OFF && i < netLevel;
            net.setAlpha(Math.round(a * (lit ? 1f : 0.3f)));
            float ro = size * WEDGE_OUT[i], ri = size * WEDGE_IN[i];
            netPath.reset();
            netArc.set(cx - ro, apexY - ro, cx + ro, apexY + ro);
            netPath.arcTo(netArc, -135, 90, true);
            if (ri > 0) {
                netArc.set(cx - ri, apexY - ri, cx + ri, apexY + ri);
                netPath.arcTo(netArc, -45, -90);
            } else {
                netPath.lineTo(cx, apexY);
            }
            netPath.close();
            c.drawPath(netPath, net);
        }
        net.setAlpha(a);
        if (state == NET_NO_INTERNET) {
            // "!" beside them
            float x = cx + size * 0.62f;
            c.drawRect(x - size * 0.05f, cy - size * 0.3f, x + size * 0.05f, cy + size * 0.14f, net);
            c.drawCircle(x, apexY - size * 0.04f, size * 0.06f, net);
        } else if (state == NET_OFF) {
            net.setStyle(Paint.Style.STROKE);
            net.setStrokeWidth(size * 0.09f);
            c.drawLine(cx - size * 0.42f, cy - size * 0.38f, cx + size * 0.42f, cy + size * 0.42f, net);
        }
    }

    /** Something started, stopped or changed in a music app: the player appears, updates or goes (MediaNow). */
    private void mediaChanged() {
        boolean on = media.shown();
        if (on != playerOn) {
            playerOn = on;
            motionDirty = true;
            heroLayer.invalidate();     // the greeting hides or comes back
            if (!on && zone == Z_PLAYER) {
                zone = Z_TOP;
                topIndex = picksOn() ? tab : 3;
                retarget(false);
            }
        }
        if (on && shownTitle != null && !media.title.equals(shownTitle)) {
            songFrom = shownTitle;      // the title change animates (drawPlayer)
            artistFrom = shownArtist;
            songAt = SystemClock.uptimeMillis();
        }
        shownTitle = on ? media.title : null;
        shownArtist = on ? media.artist : null;
        if (!on) {
            playerTextW.snap(0);
            songFrom = null;
        }
        updateMusicCard();
        updateBeat();
        playerLayer.invalidate();
        wake();
    }

    // MARK: - Liquid Glass panel

    /** The Liquid Glass panel (Settings > Liquid Glass): the look changes live on the home screen behind it. */
    void openGlassPanel() {
        if (sheetOpen) closeSheet();
        if (drawerOpen) closeDrawer();
        panelOpen = true;
        panelRow = 0;
        panel.target = 1;
        panelLayer.setVisibility(VISIBLE);
        panelLayer.invalidate();
        retarget(false);
        wake();
    }

    private void closeGlassPanel() {
        panelOpen = false;
        panel.target = 0;
        wake();
    }

    /** While the panel is open: ↑↓ a setting, ←→ change it (held: it keeps going), OK or Back closes it. */
    private boolean panelKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    int next = Math.max(0, Math.min(2, panelRow + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
                    if (next != panelRow) {
                        panelRow = next;
                        panelLayer.invalidate();
                        click.move();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) adjustLook(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
                if (!down && !e.isCanceled()) {
                    closeGlassPanel();
                    click.move();
                }
                return true;
            default:
                return false;
        }
    }

    /** One step (5) of the setting in focus; everything with glass or the background is drawn again with it. */
    private void adjustLook(int dir) {
        int before = panelRow == 0 ? Prefs.glass() : panelRow == 1 ? Prefs.brightness() : Prefs.blur();
        if (panelRow == 0) Prefs.setGlass(before + 5 * dir);
        else if (panelRow == 1) Prefs.setBrightness(before + 5 * dir);
        else Prefs.setBlur(before + 5 * dir);
        int after = panelRow == 0 ? Prefs.glass() : panelRow == 1 ? Prefs.brightness() : Prefs.blur();
        if (after == before) return;
        if (panelRow == 2) refreshGlassImage();     // the glass sees the background with its new blur
        for (View v : new View[] {sky, status, platter, drawerLayer, panelLayer}) v.invalidate();
        click.move();
        wake();
    }

    /**
     * The panel, at the right over the cards, made of the glass it sets (so it shows it too): a title, then three
     * settings, each a name, its value and a slider; the one in focus on a light pill.
     */
    private void drawGlassPanel(Canvas c) {
        if (!panelOpen && panel.value < 0.002f) return;
        applyTone();
        float pw = 620 * k, ph = 480 * k, right = CONTENT_R * k, top = 270 * k, left = right - pw;
        panelRect.set(left, top, right, top + ph);
        float r = 40 * k;
        tmp.set(panelRect);
        tmp.offset(0, 16 * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.25f, 0.45f)));
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        scene.liquid(c, panelRect, r, tone(0xFFFFFFFF, SMOKE), tone(0.5f, 0.45f), tone(0.03f, 0.02f), 30 * k, 24 * k,
                tone(0.7f, 0.6f), 1);
        int ink = tone(L_INK, WHITE), dim = tone(L_DIM, 0xFF98989D);
        panelTitle.setColor(ink);
        c.drawText("Liquid Glass", left + 44 * k, top + 66 * k, panelTitle);
        String[] names = {Ui.tr("Vidro", "Glass", "Vidrio"), Ui.tr("Brilho do fundo", "Background brightness", "Brillo del fondo"),
                Ui.tr("Desfoque do fundo", "Background blur", "Desenfoque del fondo")};
        int b = Prefs.brightness();
        String[] values = {Prefs.glass() + "%", (b > 0 ? "+" : "") + b + "%", Prefs.blur() + "%"};
        float[] amounts = {Prefs.glass() / 100f, (b + 50) / 100f, Prefs.blur() / 100f};
        fill.setShader(null);
        for (int i = 0; i < 3; i++) {
            float y = top + 100 * k + i * 112 * k;
            boolean on = i == panelRow;
            if (on) {
                cell.set(left + 18 * k, y, right - 18 * k, y + 100 * k);
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, tone(0.55f, 0.14f)));
                c.drawRoundRect(cell, 24 * k, 24 * k, fill);
            }
            panelLabel.setColor(ink);
            panelValue.setColor(on ? ink : dim);
            c.drawText(names[i], left + 44 * k, y + 40 * k, panelLabel);
            c.drawText(values[i], right - 44 * k, y + 40 * k, panelValue);
            // The slider (brightness has its middle, "as made", marked)
            float tl = left + 44 * k, tr = right - 44 * k, ty = y + 72 * k, t = 6 * k, at = tl + (tr - tl) * amounts[i];
            fill.setColor(Ui.alpha(ink, 0.22f));
            cell.set(tl, ty - t / 2, tr, ty + t / 2);
            c.drawRoundRect(cell, t / 2, t / 2, fill);
            if (i == 1) c.drawRect((tl + tr) / 2 - 1.5f * k, ty - 9 * k, (tl + tr) / 2 + 1.5f * k, ty + 9 * k, fill);
            fill.setColor(ink);
            float from = i == 1 ? (tl + tr) / 2 : tl;
            cell.set(Math.min(from, at), ty - t / 2, Math.max(from, at), ty + t / 2);
            c.drawRoundRect(cell, t / 2, t / 2, fill);
            c.drawCircle(at, ty, (on ? 13 : 10) * k, fill);
        }
        panelHint.setColor(dim);
        c.drawText(Ui.tr("↑↓ escolher · ←→ mudar · OK pronto", "↑↓ choose · ←→ change · OK done", "↑↓ elegir · ←→ cambiar · OK listo"),
                left + 44 * k, top + ph - 30 * k, panelHint);
    }

    /** Music plays now and its own cover is ready (HomeActivity's music screensaver waits for this). */
    boolean musicPlaying() {
        return playerOn && media.playing && media.artReady;
    }

    /** The bars follow the sound only while they're on screen and something plays. */
    private void updateBeat() {
        if (running && playerOn && media.playing) beat.start();
        else beat.stop();
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        if (cardIndex != 0) {
            cardIndex = 0;
            showCardBackdrop();
        }
        retarget(!animated);
        motionDirty = true;
    }

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        // The TV provider's app (EON) when there is one, else the antenna's channels
        HomeApps.Item provider = HomeApps.providerTv(getContext());
        lightButtons.add(provider != null ? provider : HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("TV aberta", "Live TV"), "live_tv"));
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entradas HDMI", "HDMI Inputs"), "hdmi"));
        lightButtons.add(HomeApps.network());    // the connection's state; opens the TV's Wi-Fi settings
        lightButtons.add(HomeApps.action(HomeApps.SETTINGS, "settings", Ui.tr("Configurações", "Settings"), "settings"));
    }

    private static String[] tabs() {
        return Ui.ru ? TABS_RU : Ui.es ? TABS_ES : Ui.de ? TABS_DE : Ui.en ? TABS_EN : TABS;
    }

    @Override public void onData() {
        wake();
        titleTemp = Double.isNaN(data.weather.temp) ? "" : HomeData.degrees(data.weather.temp);  // the title's weather
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(790 * kk));
        measure(playerLayer, Math.round(1500 * kk), Math.round(190 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(panelLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(playerLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        panelLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        layoutPreview();
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        layoutInputPreview();
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTitleDim = Ui.numbers(Ui.displayMedium, 76 * k, L_DIM);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        pTitle = Ui.text(Ui.semibold, 40 * k, L_INK);
        pArtist = Ui.text(Ui.medium, 28 * k, L_DIM);
        panelTitle = Ui.text(Ui.displaySemibold, 36 * k, L_INK);
        panelLabel = Ui.text(Ui.semibold, 27 * k, L_INK);
        panelValue = Ui.numbers(Ui.medium, 27 * k, L_DIM);
        panelValue.setTextAlign(Paint.Align.RIGHT);
        panelHint = Ui.text(Ui.medium, 22 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
        outline.setStyle(Paint.Style.STROKE);
        outline.setColor(0xFFFFFFFF);
        net.setStrokeCap(Paint.Cap.ROUND);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt);
        if (step(cardWide, dt) | cardScroll.step(dt)) {
            layoutCards();
            cardsMoving = true;
        }
        // The Liquid Glass panel slides in from the right as it fades in
        if (panel.step(dt)) {
            float p = Ui.clamp(panel.value);
            panelLayer.setAlpha(p);
            panelLayer.setTranslationX((1 - p) * 60 * k);
            panelLayer.setVisibility(p > 0.002f ? VISIBLE : INVISIBLE);
            heroMoving = true;      // keeps the frames coming
        }
        boolean playerMoving = step(playerFocus, dt) | playerTextW.step(dt)
                | (songFrom != null && up - songAt < SONG_MS + 50);
        // The equalizer dances while music plays (and sinks when it stops): only the player's small layer, ~30 a second
        boolean eq = playerOn && (media.playing || !eqSettled) && playerLayer.getVisibility() == VISIBLE;
        if (playerMoving || playerDirty || (eq && up - eqAt >= EQ_MS)) {
            playerLayer.invalidate();
            eqAt = up;
        }
        playerDirty = false;
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        inputShow.target = previewOpen() ? 1 : 0;
        boolean drawerItemsMoving = step(drawerFocus, dt) | inputShow.step(dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer moves the cached layers: redrawing all the glass on every frame made a TV drop
        // frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as they
        // rise; the drawer is drawn resting in place. The glass shelf, one shape, is redrawn on every frame of the move
        // (drawn once at rest, it showed the home position's background until the drawer stopped, then jumped): its
        // glass shows the background where it is at that moment.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || drawerMoving || sheetMoving || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        syncPreview(up);
        if (bgFading && previewMask != null && previewMask.getVisibility() == VISIBLE) previewMask.invalidate();
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (!picksOn()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty() || playerMoving;
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, eq ? EQ_MS : 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    /**
     * One page that scrolls down: the home screen, then the drawer (inputs, shortcuts, weather), then All Apps. The
     * drawer lifts everything by SHIFT; All Apps scrolls the whole page by a screen (the drawer goes up as the apps come
     * up from below), on the same background, like the rest of the page.
     */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = sheet.value;
        float top = 1 - smoothstep(0, 0.32f, dc);
        float shift = SHIFT * k, page = s * h;
        float lift = -d * shift - page;
        show(heroLayer, top, lift);
        show(playerLayer, playerOn ? top : 0, lift);
        show(status, top, lift);
        show(platter, 1, lift);
        show(tiles, 1, lift);
        float da = smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift - page);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f && s < 0.999f ? VISIBLE : INVISIBLE);
        // With the drawer open, the apps page is already there, a pixel inside the bottom edge: drawn while you look
        // at the drawer (its first drawing, with every app's picture, made the start of the scroll stutter)
        sheetLayer.setVisibility(s > 0.001f || sheetOpen || drawerOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(1);
        sheetLayer.setTranslationY((1 - s) * (h - 1));
        sheetLayer.setScaleX(1);
        sheetLayer.setScaleY(1);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        // The title's time and weather (drawTitleClock), once a minute
        titleTemp = Double.isNaN(data.weather.temp) ? "" : HomeData.degrees(data.weather.temp);
        heroLayer.invalidate();
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        wake();     // the fade needs every frame (it can start after everything else stopped moving)
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /**
     * A fade still running ends now. The glass redraws on every frame of a fade; with the cards sliding on top of that,
     * the TV dropped to ~24 fps, so a new card move first finishes the fade (the background then follows the new card).
     */
    private void finishBackdropFade() {
        if (bgFadeAt != 0) bgFadeAt = SystemClock.uptimeMillis() - FADE_MS;
    }

    /**
     * What the glass shows: the background exactly as on screen around it (Backdrop.matchedGlass: the same blur), by
     * day, at night, or blended in between.
     */
    private int[] glassOf(Backdrop b) {
        float blur = Prefs.blurAmount();
        int[] day = b.matchedGlass(false, blur);
        if (b.nightGlass == null || night <= 0.001f) return day;
        int[] dark = b.matchedGlass(true, blur);
        if (night >= 0.999f) return dark;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? day : dark;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(day[i], dark[i], night);
        return glassNight;
    }

    /** Liquid Glass > Background blur changed: the glass takes the background as it now looks. */
    private void refreshGlassImage() {
        nightPair = false;
        if (bgFadeAt == 0 && bgTo != null && glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    float blur = Prefs.blurAmount();
                    scene.setGlassImages(bgTo.matchedGlass(false, blur), bgTo.matchedGlass(true, blur));
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k, th = L_TILE_H * k;
        float full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(TILE_MAX_W * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = L_PLATTER_TOP * k;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        float right = platterRect.isEmpty() ? CONTENT_R * k : platterRect.right - SHELF_PAD * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 3; i < 3 + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        int n = HomeData.coinCount();
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        int[] coins = HomeData.active();
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            drawOutline(c, fc, r0, s);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
            drawOutline(c, fc, r0, s, cx, cy, bw, bh);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
            drawOutline(c, fc, r0, s, cx, cy, bw, bh);
        }
    }

    /** The selected app's thin outline (fading with the focus), in the tile's scaled canvas (`tmp` is the tile). */
    private void drawOutline(Canvas c, float f, float r0, float s) {
        if (f < 0.01f) return;
        outline.setStrokeWidth(2 * k / s);     // 2 px on screen, however much the tile has grown
        outline.setAlpha(Math.round(90 * f));     // a soft line, about a third of white (full white was glaring)
        c.drawRoundRect(tmp, r0, r0, outline);
    }

    /** The same, for tiles drawn without the scaled canvas. */
    private void drawOutline(Canvas c, float f, float r0, float s, float cx, float cy, float bw, float bh) {
        if (f < 0.01f) return;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        drawOutline(c, f, r0, s);
        c.restore();
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`: the image is the shader of a rounded rectangle, which
     * looks the same as clipping to the rounded shape but costs no mask (an anti-aliased clip took most of a 49 ms frame
     * on a Mi Box 3, and made the All Apps page stutter on a TCL).
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        // On every Android now: a clip path costs a mask per image, and the All Apps page draws thirty of them at once
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size: when focused it is drawn pixel for
     * pixel, without resampling.
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null);
        if (logo == null) return;
        float scale = s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width());
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || HomeApps.isLiveTv(list.get(inputs)))) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Entradas", "Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Atalhos", "Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        // A focused input: its live picture where the weather was, and the weather further down
        float show = Ui.clamp(inputShow.value);
        if (show > 0.01f && previewItem != null) drawInputPreview(c, show);
        c.save();
        c.translate(0, inputShow.value * (CARDS_H + 48) * k);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
        c.restore();
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        String name = it.label;
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(name);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(name, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = Ui.ru ? "Макс. " + HomeData.degrees(wx.max) + "   Мин. " + HomeData.degrees(wx.min)
                    : Ui.en ? "H:" + HomeData.degrees(wx.max) + "   L:" + HomeData.degrees(wx.min)
                    : Ui.de ? "H:" + HomeData.degrees(wx.max) + "   T:" + HomeData.degrees(wx.min)
                    : "Máx. " + HomeData.degrees(wx.max) + "   Mín. " + HomeData.degrees(wx.min);
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.tr("Nascer ", "Sunrise ") + Ui.time(wx.sunrise);
            sunsetText = Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(wx.sunset);
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale;
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 9 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Cotações", "Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        playerDirty |= aim(playerFocus, !sheetOpen && zone == Z_PLAYER ? playerIndex : -1, snap) || snap;
        // The last selected card stays wide while the focus is elsewhere, or on a card with its own width (the music
        // card), which never grows
        boolean own = fixedWidth(cardIndex) > 0;
        if (!own) lastWide = cardIndex;
        int wide = cardIndex;
        if (own) {
            wide = lastWide < cards.size() && fixedWidth(lastWide) <= 0 ? lastWide : -1;
            for (int i = 0; wide < 0 && i < cards.size(); i++) if (fixedWidth(i) <= 0) wide = i;
            if (wide < 0) wide = cardIndex;
        }
        float scroll = cardScrollFor(wide);
        if (cardScroll.target != scroll) {
            cardScroll.target = scroll;
            heroChanged = true;
        }
        if (snap) cardScroll.snap(scroll);
        if (aim(cardWide, wide, snap)) {
            heroChanged = true;
            if (snap) layoutCards();
        }
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        if (!drawerOpen) openDrawer();     // the page scrolls down through the drawer to the apps
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    /** All Apps, below the drawer on the same page: a row title like the drawer's, then the grid (no dark overlay). */
    private void drawSheet(Canvas c) {
        applyTone();
        float shift = scroll.value * GRID_ROW * k;
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        c.drawText(Ui.tr("Todos os Apps", "All Apps"), left + 4 * k, gridTop - 22 * k - shift, rowTitle);
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(drawerInk(0.6f), drawerInk(1), Ui.clamp(f)));     // dark by day, white at night
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
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

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    /** The app `pkg` as the shelf or All Apps has it (its name), or null if it can't be opened any more. */
    private static HomeApps.Item playedIn(Context c, String pkg, List<HomeApps.Item> shelf, List<HomeApps.Item> others) {
        for (List<HomeApps.Item> list : Arrays.asList(shelf, others)) {
            for (HomeApps.Item it : list) if (it.kind == HomeApps.APP && pkg.equals(it.pkg)) return it;
        }
        PackageManager pm = c.getPackageManager();
        if (pkg.equals(c.getPackageName()) || HomeApps.launchIntent(pm, pkg) == null) return null;
        try {
            return HomeApps.app(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            final List<NowPlaying.Item> watched = new ArrayList<>();
            final List<HomeApps.Item> apps = new ArrayList<>();
            final List<Backdrop> pictures = new ArrayList<>();
            List<NowPlaying.Item> all = NowPlaying.history(c);
            all.addAll(NetflixHistory.recent(c));
            all.sort((a, b) -> Long.compare(b.at, a.at));
            NetflixHistory.refreshIfStale(c, () -> handler.post(this::reload));
            // YouTube takes one place, its latest video (as Spotify takes one, its song): videos come one after another
            boolean youTube = false;
            for (NowPlaying.Item it : all) {
                if (it.pkg.contains("youtube")) {
                    if (youTube) continue;
                    youTube = true;
                }
                HomeApps.Item app = watched.size() < 3 ? playedIn(c, it.pkg, s, o) : null;
                Backdrop picture = app == null ? null : playedBackdrop(it);
                if (picture == null) continue;
                watched.add(it);
                apps.add(app);
                pictures.add(picture);
            }
            handler.post(() -> {
                setShelf(s);
                setPlayed(watched, apps, pictures);
                setStatusItems(st);
                setDrawerItems(d);
                if (!sheetOpen) setOthers(o);
            });
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        if (panelOpen) return panelKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
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
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held on a shelf app (~0.5 s): move mode. A timer, since not every remote repeats a held key, or the
                // long-press repeat, whichever comes first; the release after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = canReorder();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        startReorder();
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
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else if (dy > 0) {
                    openSheet();    // past the weather: All Apps
                    click.move();
                    return;
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    openSheet();    // past the weather: All Apps, below everything
                    click.move();
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow < 0) {
                // Up from the first row: the page scrolls back to the drawer
                closeSheet();
                click.move();
                return;
            }
            if (targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (!sheetOpen && zone == Z_PLAYER) {
            click.open();
            if (playerIndex == 0) media.previous();
            else if (playerIndex == 1) media.toggle();
            else media.next();
            return;
        }
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p));
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
            if (it.kind == HomeApps.INPUT) {
                previewHeld = true;     // the input goes to the TV's own player
                stopPreview();
            }
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            Watched w = watchedOf.get(p);
            it = w != null && w.app != null ? w.app : HomeApps.app(p.pkg, Recs.serviceName(p));
            r = cardRects[cardIndex];
            String video = w != null ? w.video : null;
            long netflix = w != null ? w.netflix : 0;
            // That title (the app picks it up where it stopped); the app's home if the link doesn't open
            Intent link = null;
            if (video != null && p.pkg.contains("youtube")) {
                link = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + video));
            } else if (netflix != 0 && p.pkg.equals(NetflixHistory.PKG)) {
                link = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/watch/" + netflix))
                        .putExtra("source", "30");     // what Netflix's TV app expects from a launcher
            }
            if (link != null) {
                click.open();
                try {
                    activity.startActivity(link.setPackage(p.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (ActivityNotFoundException | SecurityException e) {
                    AppLog.w("title link: " + e.getMessage());
                }
            }
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → para mover · OK para soltar · Voltar para cancelar",
                "← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
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

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
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
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
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

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        if (playerOn) for (int i = 0; i < 3; i++) out.add(a11yId(Z_PLAYER, i));
        for (int i = picksOn() ? 0 : 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(MAX_CARDS, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + (i + 1) + Ui.tr(" de ", " of ") + others.size();
            case Z_TOP:
                if (i >= 3) {
                    boolean network = i - 3 < lightButtons.size() && lightButtons.get(i - 3).kind == HomeApps.NETWORK;
                    return network ? "Wi-Fi, " + netLabel() : label(lightButtons, i - 3);
                }
                return tabs()[i] + Ui.tr(", aba ", ", tab ") + (i + 1) + Ui.tr(" de 3", " of 3");
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_PLAYER: {
                if (!playerOn) return "";
                String song = media.title + (media.artist == null ? "" : ", " + media.artist);
                return i == 0 ? Ui.tr("Faixa anterior", "Previous track", "Pista anterior")
                        : i == 2 ? Ui.tr("Próxima faixa", "Next track", "Pista siguiente")
                        : (media.playing ? Ui.tr("Pausar", "Pause", "Pausar") : Ui.tr("Tocar", "Play", "Reproducir")) + ", " + song;
            }
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.tr("Movendo ", "Moving ", "Moviendo ") + label(shelf, i)
                            + Ui.tr(", posição ", ", position ", ", posición ") + (i + 1) + Ui.tr(" de ", " of ") + shelf.size();
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(MAX_CARDS, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_PLAYER:
                if (!playerOn || i > 2) return false;
                a11yRect.set(playerRects[i]);
                dy = playerLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_PLAYER: return playerOn ? a11yId(Z_PLAYER, playerIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= 3 + lightButtons.size() || (i < 3 && !picksOn())) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 3) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_PLAYER:
                if (!playerOn || i > 2) return;
                if (drawerOpen) closeDrawer();
                playerIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("Sem dados do clima agora.", "No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay))
                .append(Ui.tr(". Máxima ", ". High ")).append(HomeData.degrees(wx.max))
                .append(Ui.tr(", mínima ", ", low ")).append(HomeData.degrees(wx.min)).append('.');
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) s.append(Ui.tr(" Próximas horas: ", " Next hours: "));
        for (int i = 0; i < hours; i++) {
            s.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i))).append(i < hours - 1 ? ", " : ".");
        }
        if (wx.sunrise != 0) s.append(Ui.tr(" Nascer do sol ", " Sunrise ")).append(Ui.time(wx.sunrise)).append('.');
        if (wx.sunset != 0) s.append(Ui.tr(" Pôr do sol ", " Sunset ")).append(Ui.time(wx.sunset)).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Cotações", "Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        cardsTop = L_CARDS_TOP * k;
        cardH = L_CARD_H * k;
        cardGap = L_CARD_GAP * k;
        cardsLeft = MARGIN * k;
        tallW = L_TALL_W * k;
        wideW = (CONTENT_R - MARGIN) * k - 2 * tallW - 2 * cardGap;
        layoutCards();
        heroDirty = statusDirty = true;
    }

    /**
     * The cards side by side: each tall, plus its share of the wide card's extra width, the row moved by the scroll (up
     * to ten in Movies and Shows; three fill the screen). The music card (Explore, first) is square: as wide as the
     * cover, never wider.
     */
    private void layoutCards() {
        int n = cardSlots();
        float fixed = 0, sum = 0;
        int flex = 0, firstFlex = -1;
        for (int i = 0; i < n; i++) {
            float f = fixedWidth(i);
            if (f > 0) {
                fixed += f;
            } else {
                flex++;
                if (firstFlex < 0) firstFlex = i;
                sum += Math.max(0, cardWide[i].value);
            }
        }
        // Three places fill the row: the cards with their own width take it, the others share the rest
        float extra = n <= 3 ? Math.max(0, wideW + 2 * tallW - fixed - flex * tallW) : wideW - tallW;
        float x = cardsLeft - cardScroll.value;
        for (int i = 0; i < MAX_CARDS; i++) {
            if (i >= n) {
                cardRects[i].setEmpty();
                continue;
            }
            float w = fixedWidth(i);
            if (w <= 0) {
                float share = sum > 0 ? Math.max(0, cardWide[i].value) / sum : i == firstFlex ? 1 : 0;
                w = tallW + extra * share;
            }
            cardRects[i].set(x, cardsTop, x + w, cardsTop + cardH);
            x += w + cardGap;
        }
    }

    /** A card's own width (the music card: square), or 0 if it shares the row's width. */
    private float fixedWidth(int i) {
        if (i >= cards.size()) return 0;
        Recs.Pick p = cards.get(i);
        return isMusic(p) ? cardH : 0;
    }

    /** Places in the row: Explore's three, or the tab's cards (at least three, so a tab still loading shows its row). */
    private int cardSlots() {
        return tab == 0 ? 3 : Math.max(3, Math.min(MAX_CARDS, cards.size()));
    }

    /**
     * Where the row scrolls for the card `wide`: it stays second, after one narrow card (the first and the last few
     * stay put, so the row never ends short of the screen's edge).
     */
    private float cardScrollFor(int wide) {
        int n = cardSlots();
        if (n <= 3) return 0;
        float total = wideW + (n - 1) * (tallW + cardGap);
        float view = wideW + 2 * (tallW + cardGap);
        return Math.max(0, Math.min(Math.max(0, wide - 1) * (tallW + cardGap), total - view));
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        // Explore: the music playing now, then what you watched (three cards in all); Movies and Shows: the picks
        List<Recs.Pick> list;
        if (tab != 0) {
            list = recs.now(TAB_KIND[tab]);
        } else {
            list = new ArrayList<>(3);
            if (picksOn()) {
                if (musicPick != null) list.add(musicPick);
                for (int i = 0; i < played.size() && list.size() < 3; i++) list.add(played.get(i));
            }
        }
        if (!samePicks(list, cards)) {
            cards = list;
            // The number of cards or the music card changed: the widths, the wide card and the scroll follow
            retarget(false);
            layoutCards();
            int was = cardIndex;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (cardIndex != was) retarget(false);
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        showCardBackdrop();
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    /** The background from the selected (wide) card. */
    private void showCardBackdrop() {
        int i = Math.min(cardIndex, cards.size() - 1);
        Recs.Pick p = i < 0 ? null : cards.get(i);
        Backdrop b = p == null ? null : backdropOf(p);
        if (p == null || b != null) showBackdrop(b);
    }

    /** A card's picture: Explore's own (watched, music) or the pick's (null until it's ready). */
    private Backdrop backdropOf(Recs.Pick p) {
        Watched w = watchedOf.get(p);
        return w != null ? w.backdrop : recs.backdrop(p);
    }

    /** What you watched (newest first, at most three), the apps that played it and the cards' pictures. */
    private void setPlayed(List<NowPlaying.Item> items, List<HomeApps.Item> apps, List<Backdrop> pictures) {
        if (items.isEmpty() && played.isEmpty()) return;
        for (Recs.Pick old : played) watchedOf.remove(old);
        List<Recs.Pick> list = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            NowPlaying.Item it = items.get(i);
            Recs.Pick p = new Recs.Pick();
            p.kind = PLAYED;
            p.id = (it.pkg + it.title).hashCode();
            p.title = it.title;
            String from = apps.get(i).label == null ? "" : apps.get(i).label;
            if (it.subtitle != null && !it.subtitle.isEmpty() && !it.subtitle.equals(from)) from += " · " + it.subtitle;
            p.meta = from;
            p.pkg = it.pkg;
            list.add(p);
            Watched w = new Watched();
            w.video = it.videoId;
            w.netflix = it.netflixId;
            w.app = apps.get(i);
            w.backdrop = pictures.get(i);
            watchedOf.put(p, w);
        }
        played = list;
        cards = new ArrayList<>();      // the same keys may now have newer pictures or another language: rebuild
        updateCards();
    }

    /** The music card from what plays now (MediaNow): made again when the song or its cover changes, gone with it. */
    private void updateMusicCard() {
        Bitmap cover = media.shown() ? media.art : null;
        Watched now = musicPick == null ? null : watchedOf.get(musicPick);
        if (cover != null && now != null && now.cover == cover && musicPick.title.equals(media.title)) return;
        if (musicPick != null) watchedOf.remove(musicPick);
        musicPick = null;
        if (cover != null) {
            final Recs.Pick p = new Recs.Pick();
            p.kind = MUSIC;
            p.id = (media.pkg + media.title).hashCode();
            p.title = media.title;
            final Watched w = new Watched();
            w.cover = cover;
            w.app = HomeApps.app(media.pkg, appLabel(media.pkg));
            p.meta = media.artist == null ? w.app.label : media.artist + " · " + w.app.label;
            p.pkg = media.pkg;
            watchedOf.put(p, w);
            musicPick = p;
            // The background: the cover on its blurred self, made in the background
            final Bitmap copy = cover.copy(Bitmap.Config.ARGB_8888, false);
            LOADER.execute(() -> {
                final Backdrop b;
                try {
                    b = Backdrop.make(albumPicture(copy), true);
                } catch (RuntimeException | OutOfMemoryError e) {
                    return;
                }
                handler.post(() -> {
                    if (musicPick != p) return;
                    w.backdrop = b;
                    if (cardIndex < cards.size() && cards.get(cardIndex) == p) showCardBackdrop();
                });
            });
        }
        cards = new ArrayList<>();
        updateCards();
    }

    /** An app's name as the shelf or All Apps shows it. */
    private String appLabel(String pkg) {
        for (List<HomeApps.Item> list : Arrays.asList(shelf, others)) {
            for (HomeApps.Item it : list) if (pkg.equals(it.pkg) && it.label != null) return it.label;
        }
        return "";
    }

    private boolean isMusic(Recs.Pick p) {
        return p != null && MUSIC.equals(p.kind);
    }

    // MARK: - The input's live picture (the drawer)

    /** The drawer's focused HDMI input with a device on it, or null. */
    private HomeApps.Item focusedInput() {
        if (!drawerOpen || sheetOpen || zone != Z_DRAWER || drawerIndex >= drawerItems.size()) return null;
        HomeApps.Item it = drawerItems.get(drawerIndex);
        return it.kind == HomeApps.INPUT && it.connected && it.inputId != null ? it : null;
    }

    /**
     * The picture opens (and the weather moves down) only for an input that sends a picture: the TvView first plays it
     * out of sight (in a corner, under its mask), and its first frame opens the place.
     */
    private boolean previewOpen() {
        HomeApps.Item it = focusedInput();
        return it != null && it.inputId.equals(signalId);
    }

    /** The picture's place: under the inputs, where the weather was (in the drawer's own coordinates). */
    private void layoutInputPreview() {
        float top = CARDS_TOP * k, ph = CARDS_H * k;
        inputPreview.set(MARGIN * k, top, MARGIN * k + ph * 16 / 9, top + ph);
    }

    /**
     * Every frame: the input plays while the drawer rests open with it focused for a moment (passing over the inputs
     * doesn't switch each one on); otherwise it stops and lets go of the input.
     */
    private void syncPreview(long up) {
        HomeApps.Item it = focusedInput();
        if (it != previewItem && it != null) {
            previewItem = it;
            previewSince = up;
            readInputSettings(it);
            drawerDirty = true;
        }
        boolean want = running && !previewHeld && it != null && up - previewSince >= PREVIEW_DELAY_MS
                && drawer.value > 0.999f && sheet.value < 0.001f;
        if (!want) {
            stopPreview();
            return;
        }
        if (preview == null) {
            preview = new TvView(getContext());
            preview.setFocusable(false);
            preview.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            preview.setCallback(previewCallback);
            // Above the drawer: its video shows through a hole in everything drawn before it
            addView(preview, indexOfChild(drawerLayer) + 1);
            previewMask = new Layer(getContext(), INPUT_MASK);
            previewMask.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(previewMask, indexOfChild(preview) + 1);
        }
        // In its place once it's open; until then a few pixels in the screen's bottom right corner, under the mask (the
        // TV sends no picture to a view off the screen), waiting for its picture
        int l, t, r, b;
        boolean shown = inputShow.value > 0.98f;
        if (shown) {
            int dy = Math.round(drawerLayer.getTranslationY());
            l = Math.round(inputPreview.left);
            t = Math.round(inputPreview.top) + dy;
            r = Math.round(inputPreview.right);
            b = Math.round(inputPreview.bottom) + dy;
        } else {
            r = w - 8;
            b = h - 8;
            l = r - 32;
            t = b - 18;
        }
        if (previewBox.left != l || previewBox.top != t || previewBox.right != r || previewBox.bottom != b
                || previewShown != shown) {
            previewBox.set(l, t, r, b);
            previewShown = shown;
            layoutPreview();
            requestLayout();
            previewMask.invalidate();
        }
        if (preview.getVisibility() != VISIBLE) {
            preview.setVisibility(VISIBLE);
            previewMask.setVisibility(VISIBLE);
            requestLayout();
            previewMask.invalidate();
        }
        if (!it.inputId.equals(previewTuned)) {
            previewTuned = it.inputId;
            videoW = videoH = 0;
            videoHz = 0;
            videoAllm = videoInterlaced = false;
            videoHdr = null;
            signalText = "";
            handler.removeCallbacks(widgetGone);    // still showing: no "gone" in between
            tclHomeWidget(1);
            try {
                preview.setStreamVolume(0f);    // the picture only: the sound stays with what plays now
                // TCL: a preview sends no HDMI-CEC (it doesn't wake the device or switch the sound system to it)
                android.os.Bundle params = new android.os.Bundle();
                params.putBoolean("isCecDeviceDisable", true);
                preview.tune(it.inputId, TvContract.buildChannelUriForPassthroughInput(it.inputId), params);
            } catch (RuntimeException e) {
                AppLog.w("input preview: " + e.getMessage());
            }
        }
    }

    /**
     * TCL: an input playing on the home screen makes its picture service take the input's picture mode, and keep it
     * after the preview ends (it believes a live TV app is in front). TCL's own home screen, with its live widget, tells
     * the service when its preview shows (1) and goes (0): option 38 of TvPqManager.setOption (libtpq's
     * setHomeWidgetStatus). The same here, in order, off the main thread. No TCL library (other TVs): nothing.
     */
    private static void tclHomeWidget(int status) {
        PQ.execute(() -> {
            try {
                Class<?> pq = Class.forName("com.tcl.tv.pq.TvPqManager");
                Object manager = pq.getMethod("getInstance").invoke(null);
                if (manager == null) return;
                Object ok = pq.getMethod("setOption", int.class, int.class, String.class)
                        .invoke(manager, 38, status, String.valueOf(status));
                AppLog.w("input preview: home widget " + status + " -> " + ok);
            } catch (ClassNotFoundException e) {
                // not a TCL TV
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                AppLog.w("input preview: home widget " + status + ": " + e);
            }
        });
    }

    private static final ExecutorService PQ = Executors.newSingleThreadExecutor();
    private final Runnable widgetGone = () -> tclHomeWidget(0);

    private final TvView.TvInputCallback previewCallback = new TvView.TvInputCallback() {
        @Override public void onVideoAvailable(String inputId) {
            AppLog.w("input preview: playing " + inputId);
            signalId = inputId;
            // TCL marks the input's app as live TV again once its picture starts (overriding the "showing" sent at the
            // tune): said again now, so that when the preview ends the service gives the home screen its picture mode
            tclHomeWidget(1);
            readVideoTrack(inputId);
            drawerDirty = true;
            wake();
        }

        @Override public void onVideoUnavailable(String inputId, int reason) {
            AppLog.w("input preview: no picture from " + inputId + " (" + reason + ")");
            // Tuning: the picture is coming (another input, or the same one again)
            if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING) return;
            if (inputId.equals(signalId)) signalId = null;
            wake();
        }

        @Override public void onVideoSizeChanged(String inputId, int width, int height) {
            videoW = width;
            videoH = height;
            describeSignal();
        }

        @Override public void onTracksChanged(String inputId, List<TvTrackInfo> tracks) {
            readVideoTrack(inputId);
        }
    };

    /** The picture's size and frame rate, from its video track (what the TV reports varies). */
    private void readVideoTrack(String inputId) {
        if (preview == null) return;
        List<TvTrackInfo> tracks;
        try {
            tracks = preview.getTracks(TvTrackInfo.TYPE_VIDEO);
        } catch (RuntimeException e) {
            return;
        }
        if (tracks == null || tracks.isEmpty()) return;
        String selected = preview.getSelectedTrack(TvTrackInfo.TYPE_VIDEO);
        TvTrackInfo track = tracks.get(0);
        for (TvTrackInfo t : tracks) if (t.getId().equals(selected)) track = t;
        if (track.getVideoWidth() > 0) {
            videoW = track.getVideoWidth();
            videoH = track.getVideoHeight();
        }
        if (track.getVideoFrameRate() > 0) videoHz = track.getVideoFrameRate();
        android.os.Bundle extra = track.getExtra();
        if (extra != null) {
            // TCL's details on the signal (HDMI's own): ALLM (the console asks for game mode), interlaced, the HDR type
            videoAllm = extra.getBoolean("isAllm", false);
            videoInterlaced = extra.getBoolean("isInterlace", false);
            videoHdr = extra.containsKey("hdrType") ? String.valueOf(extra.get("hdrType")) : null;
            AppLog.w("input preview: video track " + track.getVideoWidth() + "x" + track.getVideoHeight() + " "
                    + track.getVideoFrameRate() + " Hz " + extra);
        }
        describeSignal();
    }

    /** "4K · 3840 × 2160 · 60 Hz": the line under the input's name. */
    private void describeSignal() {
        StringBuilder sb = new StringBuilder();
        if (videoW > 0 && videoH > 0) {
            int lines = Math.min(videoW * 9 / 16, videoH);
            String name = videoW >= 7680 ? "8K" : videoW >= 3840 ? "4K" : lines >= 1440 ? "1440p" : lines >= 1080 ? "1080p"
                    : lines >= 720 ? "720p" : lines >= 576 ? "576p" : lines >= 480 ? "480p" : "";
            if (videoInterlaced && name.endsWith("p")) name = name.substring(0, name.length() - 1) + "i";
            if (!name.isEmpty()) sb.append(name).append(" · ");
            sb.append(videoW).append(" × ").append(videoH);
        }
        if (videoHz > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(Math.round(videoHz)).append(" Hz");
        }
        if (videoAllm) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("ALLM");
        }
        signalText = sb.toString();
        drawerDirty = true;
        wake();
    }

    /** What the TV's own settings say about this port (TCL): 144 Hz VRR. */
    private void readInputSettings(HomeApps.Item it) {
        portText = "HDMI " + it.number;
        try {
            if (android.provider.Settings.Secure.getInt(getContext().getContentResolver(), "tcl_sys_hdmi_144vrr_config_hdmi" + it.number, 0) == 1) {
                portText += " · VRR 144 Hz";
            }
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * The live picture in its place, and its mask around it. Also in every layout pass: the TvView adds and resizes its
     * video surface itself, and that surface only comes once the TvView is laid out with it.
     */
    private void layoutPreview() {
        if (preview == null || previewBox.isEmpty()) return;
        Rect b = previewBox;
        measure(preview, b.width(), b.height());
        preview.layout(b.left, b.top, b.right, b.bottom);
        int m = Math.round(4 * k);
        measure(previewMask, b.width() + 2 * m, b.height() + 2 * m);
        previewMask.layout(b.left - m, b.top - m, b.right + m, b.bottom + m);
    }

    private void stopPreview() {
        signalId = null;
        if (preview == null || (previewTuned == null && preview.getVisibility() != VISIBLE)) return;
        if (previewTuned != null) {
            preview.reset();
            // "Gone" makes the service apply the current source's picture mode again: only once the TV is back on its
            // home source (TCL's input service switches it a moment after the release), else it's the input's that stays
            handler.postDelayed(widgetGone, 1000);
        }
        previewTuned = null;
        preview.setVisibility(GONE);
        previewMask.setVisibility(GONE);
    }

    /** Over the live picture: the background back over its square corners (all of it, while it waits in the corner). */
    private void drawPreviewMask(Canvas c) {
        if (previewBox.isEmpty()) return;
        cell.set(previewBox);
        float r = 30 * k;
        previewCorners.reset();
        previewCorners.setFillType(Path.FillType.EVEN_ODD);
        previewCorners.addRect(cell, Path.Direction.CW);
        if (previewShown) previewCorners.addRoundRect(cell, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(previewCorners);
        drawLightSky(c);
        c.restore();
    }

    /**
     * Under the live picture (seen for a moment while the next input's picture comes): a dark card with the input's
     * symbol; beside it, the input's name, its port and the signal. Fades in with `show`.
     */
    private void drawInputPreview(Canvas c, float show) {
        HomeApps.Item it = previewItem;
        RectF r = inputPreview;
        float rad = 30 * k;
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF101012, show));
        c.drawRoundRect(r, rad, rad, fill);
        if (it.symbol != null) Symbols.draw(getContext(), c, it.symbol, r.centerX(), r.centerY(), 64 * k, Ui.alpha(0x8CFFFFFF, show));
        float x = r.right + 44 * k;
        int a = cardText.getAlpha();
        cardText.setAlpha(Math.round(a * show));
        c.drawText(it.label == null ? "" : it.label, x, r.top + 60 * k, cardText);
        cardText.setAlpha(a);
        a = cardDim.getAlpha();
        cardDim.setAlpha(Math.round(a * show));
        float y = r.top + 104 * k;
        if (!portText.isEmpty()) {
            c.drawText(portText, x, y, cardDim);
            y += 40 * k;
        }
        if (!signalText.isEmpty()) c.drawText(signalText, x, y, cardDim);
        cardDim.setAlpha(a);
    }

    /**
     * A watched card's picture and the background it gives Explore when it comes first, made like the picks' (Backdrop).
     * In the background; kept while it is on the list, so coming back home doesn't redo them.
     */
    private static Backdrop playedBackdrop(NowPlaying.Item it) {
        String key = it.pkg + "\n" + it.title + "\n" + it.at;
        synchronized (PLAYED_BACKDROPS) {
            Backdrop b = PLAYED_BACKDROPS.get(key);
            if (b != null) return b;
        }
        Backdrop b;
        try {
            Bitmap src = NowPlaying.music(it.image) ? albumPicture(it.image) : it.image;
            b = Backdrop.make(src, true);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("watched picture: " + e.getMessage());
            return null;
        }
        synchronized (PLAYED_BACKDROPS) {
            PLAYED_BACKDROPS.put(key, b);
            while (PLAYED_BACKDROPS.size() > 4) PLAYED_BACKDROPS.remove(PLAYED_BACKDROPS.keySet().iterator().next());
        }
        return b;
    }

    /**
     * A square cover (music) as a 16:9 picture: the cover whole in the middle, on a blurred and darkened copy of itself,
     * sized to fit both the wide card and the narrow ones (each cut from the middle of this picture).
     */
    private static Bitmap albumPicture(Bitmap art) {
        int w = 1280, h = 720;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG | Paint.ANTI_ALIAS_FLAG);
        // Behind: shrunk to a few pixels and stretched back, that is, blurred
        Bitmap tiny = Bitmap.createScaledBitmap(art, 10, Math.max(1, 10 * art.getHeight() / art.getWidth()), true);
        c.drawBitmap(tiny, null, new RectF(0, 0, w, h), p);
        tiny.recycle();
        c.drawColor(0x66000000);
        float side = 430;
        RectF cover = new RectF((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setColor(0x73000000);
        shade.setMaskFilter(new android.graphics.BlurMaskFilter(28, android.graphics.BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(cover.left, cover.top + 14, cover.right, cover.bottom + 14, 14, 14, shade);
        BitmapShader shader = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(side / art.getWidth(), side / art.getHeight());
        m.postTranslate(cover.left, cover.top);
        shader.setLocalMatrix(m);
        p.setShader(shader);
        c.drawRoundRect(cover, 14, 14, p);
        art.recycle();
        return out;
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        cardIndex = 0;      // a new tab starts with its first card wide
        retarget(false);
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
        drawBrightness(c);
    }

    /**
     * The background photo, by day and/or at night. Liquid Glass > Background blur: below full, the less blurred version
     * shows under the fully blurred one (which fades with the setting).
     */
    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        float blur = Prefs.blurAmount();
        if (night < 0.999f || b.nightImage == null) drawBlurred(c, b.lightImage, blur < 0.999f ? b.sharpImage(false) : null, blur, alpha);
        if (night > 0.001f) drawBlurred(c, b.nightImage, blur < 0.999f ? b.sharpImage(true) : null, blur, alpha * night);
    }

    private void drawBlurred(Canvas c, Bitmap blurred, Bitmap sharp, float blur, float alpha) {
        if (sharp == null) {
            drawImage(c, blurred, alpha);
            return;
        }
        drawImage(c, sharp, alpha);
        drawImage(c, blurred, alpha * blur);
    }

    /** Liquid Glass > Background brightness: the background lighter (white over it) or darker (black). */
    private void drawBrightness(Canvas c) {
        float b = Prefs.brightnessAmount();
        if (Math.abs(b) < 0.005f) return;
        fill.setShader(null);
        fill.setColor(b > 0 ? Ui.alpha(0xFFFFFFFF, b * 0.7f) : Ui.alpha(0xFF000000, -b * 0.9f));
        c.drawRect(screen, fill);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (!picksOn()) {
            drawClock(c);
            if (HomeData.coinCount() > 0) drawQuotes(c);
            return;
        }
        if (!playerOn) drawTitleClock(c);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        // Explore shows only what you watched: no empty slots, and a line saying so before anything played
        int count = tab == 0 ? Math.min(3, cards.size()) : cardSlots();
        int top = -1;
        for (int i = 0; i < count; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < count; i++) if (i != top && onScreen(cardRects[i])) drawLightCard(c, i);
        if (top >= 0 && top < count) drawLightCard(c, top);
        if (count == 0) {
            String hint = Ui.tr("O que você assistir aparece aqui.", "What you watch shows up here.", "Lo que veas aparece aquí.");
            c.drawText(hint, MARGIN * k, (L_CARDS_TOP + 60) * k, lCardMeta);
        }
        if (HomeData.coinCount() > 0) drawQuotes(c);
    }

    /**
     * The title (when no music plays): the time, then the weather now (its symbol and the temperature, in a dimmer
     * color), on one line.
     */
    private void drawTitleClock(Canvas c) {
        float x = MARGIN * k - Ui.leftBearing(lTitle, clockText), base = L_TITLE_BASE * k;
        c.drawText(clockText, x, base, lTitle);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        x += lTitle.measureText(clockText) + 40 * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float size = 62 * k, sw = Symbols.width(getContext(), symbol, size);
        lTitleDim.setColor(tone(L_DIM, 0xFF98989D));
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lTitle), size, lTitleDim.getColor());
        c.drawText(titleTemp, x + sw + 14 * k, base, lTitleDim);
    }

    /** A card (scrolled) is at least partly on screen: the ones off it aren't drawn, nor their pictures loaded. */
    private boolean onScreen(RectF r) {
        return r.right > -40 * k && r.left < w + 40 * k;
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + (Ui.ru ? "Макс. " + HomeData.degrees(wx.max) + "  Мин. " + HomeData.degrees(wx.min)
                    : Ui.en ? "H:" + HomeData.degrees(wx.max) + "  L:" + HomeData.degrees(wx.min)
                    : Ui.de ? "H:" + HomeData.degrees(wx.max) + "  T:" + HomeData.degrees(wx.min)
                    : "Máx. " + HomeData.degrees(wx.max) + "  Mín. " + HomeData.degrees(wx.min));
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.drawSliced(c, tmp, r0, DEEP_BLUR * k, shadowDeep);     // the card's width changes every frame
        c.restore();
        // The wide picture, cut to the card's shape of the moment (it narrows and widens as the selection moves); the
        // music card: the square cover as it is
        Watched w = p == null ? null : watchedOf.get(p);
        Backdrop b = p == null ? null : w != null ? w.backdrop : recs.backdrop(p);
        if (w != null && w.cover != null) {
            coverImage(c, w.cover, cell, r);
        } else if (b != null && b.wide != null) {
            coverImage(c, b.wide, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        drawCardRim(c, cx, cy, bw, bh, s, r0, f);
        if (p == null) return;
        c.drawText(fit(FIT_TITLE + i, p.title, lCardTitle, bw), base.left, base.bottom + 46 * k, lCardTitle);
        // The cards with their own width (never wide: music) always show their details
        float wide = fixedWidth(i) > 0 ? 1 : Ui.clamp(cardWide[i].value);
        if (wide > 0.3f && p.meta != null && !p.meta.isEmpty()) {
            // Details only under the wide card, fading in as it grows
            int metaAlpha = lCardMeta.getAlpha();
            lCardMeta.setAlpha(Math.round(metaAlpha * smoothstep(0.3f, 0.9f, wide)));
            c.drawText(fit(FIT_META + i, p.meta, lCardMeta, bw), base.left, base.bottom + 80 * k, lCardMeta);
            lCardMeta.setAlpha(metaAlpha);
        }
    }

    /** The focused card's sheen and rim (fading in with `f`), on the card centered at cx, cy grown by `s`. */
    private void drawCardRim(Canvas c, float cx, float cy, float bw, float bh, float s, float r0, float f) {
        if (f <= 0.01f) return;
        // Made once for a 1×1 card and stretched to this one (its width changes while it grows)
        if (lightSheen == null) {
            lightSheen = new LinearGradient(-0.5f, -0.5f, 0, 0.5f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            lightRim = new LinearGradient(0, -0.5f, 0, 0.5f, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
        }
        unitMatrix.setScale(bw, bh);
        lightSheen.setLocalMatrix(unitMatrix);
        lightRim.setLocalMatrix(unitMatrix);
        int alpha = Math.round(255 * f);
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        sheen.setShader(lightSheen);
        sheen.setAlpha(alpha);
        c.drawRoundRect(tmp, r0, r0, sheen);
        sheen.setAlpha(255);
        rim.setShader(lightRim);
        rim.setAlpha(alpha);
        rim.setStrokeWidth(2.5f * k / s);
        c.drawRoundRect(tmp, r0, r0, rim);
        rim.setStrokeWidth(2.5f * k);
        rim.setAlpha(255);
        c.restore();
    }

    /**
     * `s` shortened with "…" to `width` (in 8 px steps), kept per `slot` (card title or details) so a card that changes
     * width on every frame doesn't measure and allocate the text again each frame.
     */
    private String fit(int slot, String s, Paint p, float width) {
        int steps = (int) (width / 8);
        if (s.equals(fitSource[slot]) && steps == fitSteps[slot] && p.getTextSize() == fitSize[slot]) return fitText[slot];
        fitPaint.set(p);
        fitText[slot] = TextUtils.ellipsize(s, fitPaint, steps * 8, TextUtils.TruncateAt.END).toString();
        fitSource[slot] = s;
        fitSteps[slot] = steps;
        fitSize[slot] = p.getTextSize();
        return fitText[slot];
    }

    /**
     * `image` cut to `cell`'s shape around its center and fitted to it, with corners of radius `r`: the image is the
     * shader of a rounded rectangle on every Android (a clip path that changes size each frame costs the GPU a new mask).
     */
    private void coverImage(Canvas c, Bitmap image, RectF cell, float r) {
        int iw = image.getWidth(), ih = image.getHeight();
        float aspect = cell.width() / Math.max(1, cell.height());
        float sw = Math.min(iw, ih * aspect);
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        float scale = cell.width() / sw;
        imageMatrix.setScale(scale, scale);
        imageMatrix.postTranslate(cell.centerX() - iw * scale / 2, cell.centerY() - ih * scale / 2);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Live TV (or the TV provider's app), HDMI Inputs, Wi-Fi and Settings: icon-only glass circles (light by day,
     * smoked at night); the focused one turns white with a dark icon (the tvOS focus) and its name appears below it.
     * Wi-Fi's icon is the connection itself (drawNetwork) and its name what the connection is.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            HomeApps.Item it = lightButtons.get(i);
            boolean network = it.kind == HomeApps.NETWORK;
            int ink = blend(tone(L_INK, 0xEBFFFFFF), L_INK, f);
            if (network) drawNetwork(c, cell.centerX(), cell.centerY(), 30 * k * s, ink);
            else Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), 27 * k * s, ink);
            if (f > 0.01f) {
                String label = network ? netLabel() : it.label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
    }

    /**
     * The player where the greeting was: equalizer bars (dancing while it plays, resting when paused), the song and the
     * artist, then ⏮ ⏯ ⏭ as round buttons like the corner ones (focused: white, a little bigger, with a shadow).
     */
    private void drawPlayer(Canvas c) {
        if (!playerOn || media.title == null) return;
        int ink = tone(L_INK, WHITE);
        pTitle.setColor(ink);
        pArtist.setColor(tone(L_DIM, 0xFF98989D));
        float x = MARGIN * k, barW = 8 * k, barGap = 6 * k, bottom = 118 * k, tallest = 44 * k;
        long now = SystemClock.uptimeMillis();
        double t = now / 1000.0;
        float dt = eqDrawnAt == 0 ? 0 : Math.min(0.1f, (now - eqDrawnAt) / 1000f);
        eqDrawnAt = now;
        fill.setShader(null);
        fill.setColor(ink);
        boolean live = beat.live();
        boolean settled = true;
        for (int i = 0; i < 4; i++) {
            // The sound's loudness per band (Beat); where the TV gives no sound to read, their own dance. Paused, they
            // sink to rest: each bar eases toward its height (up fast, down slower)
            float level = live ? beat.level(i) : (float) Math.abs(Math.sin(t * EQ_SPEED[i] + EQ_PHASE[i]));
            float target = media.playing ? EQ_REST + (1 - EQ_REST) * level : EQ_REST;
            if (dt == 0) eqShown[i] = target;
            else eqShown[i] += (target - eqShown[i]) * (1 - (float) Math.exp(-dt * (target > eqShown[i] ? 22 : 7)));
            settled &= Math.abs(target - eqShown[i]) < 0.01f;
            float bar = tallest * eqShown[i];
            float left = x + i * (barW + barGap);
            tmp.set(left, bottom - bar, left + barW, bottom);
            c.drawRoundRect(tmp, barW / 2, barW / 2, fill);
        }
        eqSettled = settled;
        // A new song: the old title rises and fades out while the new one rises in; the buttons slide to fit it
        float tx = x + 4 * (barW + barGap) + 20 * k, maxW = 760 * k, lift = 14 * k;
        String song = fit(FIT_PLAYER, media.title, pTitle, maxW);
        String by = media.artist == null ? "" : fit(FIT_PLAYER + 1, media.artist, pArtist, maxW);
        float p = songFrom == null ? 1 : Ui.clamp((now - songAt) / (float) SONG_MS);
        float in = p * p * (3 - 2 * p);
        if (p < 1) {
            String oldSong = fit(FIT_PLAYER + 2, songFrom, pTitle, maxW);
            String oldBy = artistFrom == null ? "" : fit(FIT_PLAYER + 3, artistFrom, pArtist, maxW);
            int a = Math.round(255 * (1 - Ui.clamp(in * 1.6f)));
            pTitle.setAlpha(a);
            pArtist.setAlpha(a);
            c.drawText(oldSong, tx, 92 * k - lift * in, pTitle);
            c.drawText(oldBy, tx, 132 * k - lift * in, pArtist);
        }
        int a = Math.round(255 * Ui.clamp(p < 1 ? (in - 0.25f) / 0.75f : 1));
        pTitle.setAlpha(a);
        pArtist.setAlpha(a);
        c.drawText(song, tx, 92 * k + lift * (1 - in), pTitle);
        c.drawText(by, tx, 132 * k + lift * (1 - in), pArtist);
        pTitle.setAlpha(255);
        pArtist.setAlpha(255);
        if (p >= 1) songFrom = null;
        float textW = Math.max(pTitle.measureText(song), pArtist.measureText(by));
        if (playerTextW.target != textW) {
            playerTextW.target = textW;
            if (playerTextW.value == 0) playerTextW.snap(textW);    // first time: in place
        }
        float d = 64 * k, cy = 100 * k;
        float cx = tx + playerTextW.value + 44 * k + d / 2;
        for (int i = 0; i < 3; i++) {
            float f = Ui.clamp(playerFocus[i].value), rad = d / 2 * (1 + 0.1f * f);
            playerRects[i].set(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2);
            if (f > 0.01f) {
                tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
                tmp.offset(0, 8 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * f));
                shadows.drawSliced(c, tmp, rad, SHADOW_BLUR * k, shadow);
            }
            fill.setColor(blend(tone(0x1A000000, 0x2EFFFFFF), 0xFFFFFFFF, f));
            c.drawCircle(cx, cy, rad, fill);
            drawGlyph(c, i, cx, cy, 24 * k * (1 + 0.1f * f), blend(ink, L_INK, f));
            cx += d + 22 * k;
        }
    }

    /** ⏮ (0), ⏸ or ▶ (1), ⏭ (2), drawn as shapes `size` high around (cx, cy). */
    private void drawGlyph(Canvas c, int which, float cx, float cy, float size, int color) {
        fill.setColor(color);
        glyph.reset();
        float half = size / 2;
        if (which == 1 && media.playing) {
            float bar = size * 0.3f, gap = size * 0.24f;
            glyph.addRect(cx - gap / 2 - bar, cy - half, cx - gap / 2, cy + half, Path.Direction.CW);
            glyph.addRect(cx + gap / 2, cy - half, cx + gap / 2 + bar, cy + half, Path.Direction.CW);
        } else if (which == 1) {
            float w = size * 0.9f;
            glyph.moveTo(cx - w * 0.38f, cy - half);
            glyph.lineTo(cx + w * 0.62f, cy);
            glyph.lineTo(cx - w * 0.38f, cy + half);
            glyph.close();
        } else {
            // A triangle pointing back (or ahead) with a bar at its tip
            float dir = which == 0 ? -1 : 1, w = size * 0.7f, bar = size * 0.16f;
            float base = cx - dir * w * 0.55f, tip = cx + dir * w * 0.35f;
            glyph.moveTo(base, cy - half);
            glyph.lineTo(tip, cy);
            glyph.lineTo(base, cy + half);
            glyph.close();
            float barX = tip + dir * bar * 0.2f;
            glyph.addRect(Math.min(barX, barX + dir * bar), cy - half, Math.max(barX, barX + dir * bar), cy + half, Path.Direction.CW);
        }
        c.drawPath(glyph, fill);
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                if (dy >= 0 || shelf.isEmpty()) return false;
                if (cards.isEmpty()) {
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : 3;
                } else {
                    zone = Z_CARDS;
                    cardIndex = Math.min(cardIndex, cards.size() - 1);  // back to the wide (selected) card
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                    finishBackdropFade();
                    retarget(false);
                    // The background follows once the selection rests: its cross-fade redraws the glass, and on top
                    // of the cards' slide that dropped frames
                    handler.removeCallbacks(cardBackdropLater);
                    handler.postDelayed(cardBackdropLater, 600);
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_TOP:
                if (dx < 0 && topIndex == 3 && playerOn) {
                    // Left of the first round button: the player's ⏭, on the same row (the tabs are below)
                    zone = Z_PLAYER;
                    playerIndex = 2;
                } else if (dx != 0) {
                    int next = Math.max(picksOn() ? 0 : 3, Math.min(2 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < 3) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = Math.min(cardIndex, cards.size() - 1);  // back to the wide (selected) card
                    }
                } else if (playerOn && topIndex < 3) {
                    // Up from the tabs: the player (the round buttons are on its row already)
                    zone = Z_PLAYER;
                    playerIndex = 1;    // play/pause first
                } else if (topIndex < 3) {
                    topIndex = 3;       // nothing playing: up from the tabs to the round buttons, the first one
                } else {
                    return true;
                }
                break;
            case Z_PLAYER:
                if (dx > 0 && playerIndex == 2) {
                    // The player and the round buttons share the top row: right of ⏭ comes the first button
                    zone = Z_TOP;
                    topIndex = 3;
                } else if (dx != 0) {
                    int next = Math.max(0, Math.min(2, playerIndex + dx));
                    if (next == playerIndex) return true;
                    playerIndex = next;
                } else if (dy > 0) {
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : 3;
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
