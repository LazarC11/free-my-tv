package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen): a menu on the left (Home screen,
 * Apps, Side bar, Background, Screensaver, Clock & weather, Remote buttons, Free My TV mode) and the chosen part in a
 * panel on the right. "Free My TV mode" asks for the switch; it runs in the main process (HomeMode)
 * and this screen only follows the state file. On the home screen the same view opens over it (HomeActivity).
 */
public class SettingsActivity extends Activity implements SettingsHost {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (openFireHome(getIntent())) return;
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this, this);
        setContentView(view);
    }

    @Override public boolean overHome() {
        return false;
    }

    /**
     * Fire TV: Amazon keeps the Home button, so the app's icon opens the home screen (FireHomeActivity) directly;
     * the gear on the home screen opens these settings (no launcher category).
     */
    private boolean openFireHome(Intent intent) {
        if (!HomeMode.AMAZON || intent == null || !(intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                || intent.hasCategory(Intent.CATEGORY_LAUNCHER)) || intent.getBooleanExtra("screensaver", false)) {
            return false;
        }
        startActivity(new Intent(this, FireHomeActivity.class));
        finish();
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (view == null) return;
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openFireHome(intent)) return;
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        if (view != null) view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int S_HOME = 0, S_APPS = 1, S_RAIL = 2, S_BACKGROUND = 3, S_SAVER = 4, S_CLOCK = 5,
                S_KEYS = 6, S_MODE = 7, SECTIONS = 8;
        private static final String[] SECTION_SYMBOLS = {"home", "apps", "tv", "sunny", "bedtime", "partly_cloudy_day",
                "gamepad", "sparkles"};
        // Layout in 1080p pixels: the menu panel, the right panel and its rows.
        private static final float MENU_L = 220, MENU_R = 638, MENU_B = 782, PANEL_T = 192, ITEM_T = 207, ITEM_PITCH = 70,
                ITEM_H = 64;
        private static final float PANE_L = 672, PANE_R = 1700, PANE_B = 1018, ROW_L = 719, ROW_R = 1652, ROW_R_RAIL = 1331;
        private static final float ROWS_T = 318, ROW_PITCH = 60, ROW_H = 56, RADIUS = 44, HINT_Y = 981;
        private static final int VISIBLE = 10;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E, GREEN = 0xFF30D158, WHITE = 0xFFF5F5F7;
        // Row kinds: a switch, a value the arrows change, a link (opens a list or asks), "Always",
        // a choice with a check, an action.
        private static final int K_TOGGLE = 0, K_VALUE = 1, K_LINK = 2, K_ALWAYS = 3, K_CHECK = 5, K_ACTION = 6;

        /** A row of the right panel. */
        private static final class Row {
            final int kind;
            final String key, label;
            String symbol, sub = "", value = "";
            boolean on, handle;
            Bitmap icon;

            Row(int kind, String key, String label) {
                this.kind = kind;
                this.key = key;
                this.label = label;
            }
        }

        private final Activity activity;
        private final SettingsHost host;
        private final Scene scene = new Scene();
        private final Glass.Panel glass = new Glass.Panel();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[VISIBLE], menuPop = new Spring[SECTIONS];
        // A list picked from inside the right panel (language, city).
        private final List<String> pickLabels = new ArrayList<>();
        private boolean picking;
        private int pickFor, pickCurrent, pickReturn;
        private static final int PICK_CITY = 0, PICK_LANGUAGE = -1;   // above 0: a remote button (index + 1)
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list editors (the shelf's apps, the side bar, the screensaver scenes): every candidate as {key, label,
        // sub, symbol}, the selected ones first and in order.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private static final int ED_NONE = -1, ED_SHELF = 0, ED_RAIL = 1, ED_SCENES = 2;
        private int editing = ED_NONE;
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        // The side bar as it is (the preview at the left and in Side bar), and what each of its keys draws.
        private final Map<String, HomeApps.Item> railByKey = new HashMap<>();
        private final List<String> railKeys = new ArrayList<>();
        private final Map<String, Bitmap> appIcons = new HashMap<>();
        private Bitmap logo;
        private final RectF tmp = new RectF(), rowRect = new RectF(), box = new RectF(), pill = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey, wasBusy;
        private long pendingAt;
        // section: the menu's choice; col 0 is the menu, 1 the right panel (focus and scroll are its rows).
        private int section, col, focus, scroll;
        private boolean moving, held;   // held: OK is down long enough to lift the row (then ▲▼ move it)
        private boolean running, okDown, backDown;
        private List<Row> rows = new ArrayList<>();
        private final Spoken spoken;
        private long startedAt, lastNanos, lastSky;
        private int w, h;
        private float k = 1;
        private Paint pageTitle, paneTitle, paneSub, menuLabel, rowLabel, rowValue, rowSub, small, header, cellLabel,
                cellValue, warn, spinner, white, shadow, fill, image, chipText, hintText, version, arc;
        private TextPaint notice;
        private Shader dim;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        SettingsView(Activity a, SettingsHost host) {
            super(a);
            activity = a;
            this.host = host;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            for (int i = 0; i < menuPop.length; i++) menuPop[i] = new Spring(0.28f, 0.85f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            if ("TCL".equalsIgnoreCase(Build.BRAND) || "TCL".equalsIgnoreCase(Build.MANUFACTURER)) {
                try (InputStream in = a.getAssets().open("brand/tcl.png")) {
                    logo = android.graphics.BitmapFactory.decodeStream(in);
                } catch (IOException ignored) {
                }
            }
            setFocusable(true);
            setContentDescription("Free My TV");
            // TalkBack: one item per menu entry or per row on screen, id = index + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= itemCount(); i++) out.add(i); }
                @Override public String text(int id) {
                    if (id < 1 || id > itemCount()) return "";
                    return col == 0 ? sectionName(id - 1) : rowText(rows.get(scroll + id - 1));
                }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > itemCount()) return false;
                    (col == 0 ? menuBox(id - 1) : rowBox(id - 1)).round(out);
                    if (host.overHome()) out.offset(-Math.round(OVER_SHIFT * k), 0);
                    return true;
                }
                @Override public int focused() { return (col == 0 ? section : focus) + 1; }
                @Override public void focus(int id) {
                    if (col == 0) selectSection(id - 1); else focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        private int itemCount() {
            return col == 0 ? SECTIONS : visibleRows();
        }

        /** The right panel's rows on screen (whichever side has the focus). */
        private int visibleRows() {
            return Math.max(0, Math.min(VISIBLE, rows.size() - scroll));
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(Row r) {
            String sub = r.sub.isEmpty() ? "" : ", " + r.sub;
            if (r.kind == K_CHECK) return r.label + sub + (r.on ? Ui.tr(", selected") : "") + (r.value.isEmpty() ? "" : ", " + r.value);
            String v = r.kind == K_TOGGLE ? onOff(r.on) : r.kind == K_ALWAYS ? Ui.tr("Always") : r.value;
            return v.isEmpty() ? r.label + sub : r.label + sub + ", " + v;
        }

        private RectF menuBox(int i) {
            float top = ITEM_T + i * ITEM_PITCH;
            box.set((MENU_L + 18) * k, top * k, (MENU_R - 18) * k, (top + ITEM_H) * k);
            return box;
        }

        private RectF rowBox(int i) {
            float top = ROWS_T + i * ROW_PITCH;
            box.set(ROW_L * k, top * k, rowRight() * k, (top + ROW_H) * k);
            return box;
        }

        private float rowRight() {
            return section == S_RAIL && !picking ? ROW_R_RAIL : ROW_R;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            loadRail();
            selectSection(section);
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
            Photos.importInbox(getContext(), this::invalidate);   // photos a computer just sent
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            for (Spring s : menuPop) moving |= !s.settled();
            // The sky moves on its own (10 fps); over the home screen's still photo only changes redraw.
            if (moving || (!host.overHome() && SystemClock.uptimeMillis() - lastSky >= 100)) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            glass.k = k;
            scene.setSize(w, h);
            for (String s : SECTION_SYMBOLS) Symbols.warm(getContext(), s, 28 * k);
            for (String s : new String[] {"chevron_right", "check", "play", "tv_settings", "home", "settings", "hdmi"}) {
                Symbols.warm(getContext(), s, 26 * k);
            }
            pageTitle = Ui.text(Ui.displaySemibold, 56 * k, 0xFFFFFFFF);
            paneTitle = Ui.text(Ui.displaySemibold, 38 * k, 0xFFFFFFFF);
            paneSub = Ui.text(Ui.regular, 22 * k, 0x99FFFFFF);
            menuLabel = Ui.text(Ui.medium, 27 * k, 0xF2FFFFFF);
            rowLabel = Ui.text(Ui.medium, 25 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 19 * k, 0x8CFFFFFF);
            small = Ui.text(Ui.regular, 19 * k, 0x73FFFFFF);
            small.setTextAlign(Paint.Align.RIGHT);
            header = Ui.text(Ui.semibold, 19 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 22 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 22 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 21 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 21 * k, 0xB3FFFFFF));
            chipText = Ui.text(Ui.semibold, 20 * k, 0xFFFFFFFF);
            hintText = Ui.text(Ui.regular, 20 * k, 0x99FFFFFF);
            version = Ui.text(Ui.regular, 20 * k, 0x59FFFFFF);
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            arc = new Paint(Paint.ANTI_ALIAS_FLAG);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(WHITE);
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            // The sky, darker at the top and the bottom, so the glass reads like the design's.
            dim = new LinearGradient(0, 0, 0, h, new int[] {0xE6000000, 0x73000000, 0x6B000000, 0xEB000000},
                    new float[] {0, 0.45f, 0.62f, 1}, Shader.TileMode.CLAMP);
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        // MARK: - Sections and their rows

        private static String sectionName(int i) {
            switch (i) {
                case S_HOME: return Ui.tr("Home screen");
                case S_APPS: return Ui.tr("Apps");
                case S_RAIL: return Ui.tr("Side bar");
                case S_BACKGROUND: return Ui.tr("Background");
                case S_SAVER: return Ui.tr("Screensaver");
                case S_CLOCK: return Ui.tr("Clock & weather");
                case S_KEYS: return Ui.tr("Remote buttons");
                default: return Ui.tr("Free My TV mode");
            }
        }

        private static String sectionSub(int i) {
            switch (i) {
                case S_HOME: return Ui.tr("Language, look and sounds.");
                case S_APPS: return Ui.tr("The apps on the shelf, and their order.");
                case S_RAIL: return Ui.tr("Choose what's in it and in which order.");
                case S_BACKGROUND: return Ui.tr("What's behind the home screen.");
                case S_SAVER: return Ui.tr("The scenes it plays, and a preview.");
                case S_CLOCK: return Ui.tr("The weather beside the clock, and its city.");
                case S_KEYS: return Ui.tr("What each button on the remote opens.");
                default: return Ui.tr("Free My TV, or the TV's own home screen.");
            }
        }

        /** The menu moved to section `i`: the right panel shows it from the top. */
        private void selectSection(int i) {
            section = Math.max(0, Math.min(SECTIONS - 1, i));
            scroll = focus = 0;
            moving = picking = false;
            editing = section == S_APPS ? ED_SHELF : section == S_RAIL ? ED_RAIL : section == S_SAVER ? ED_SCENES : ED_NONE;
            if (editing != ED_NONE) openEditor(editing);
            if (section == S_KEYS) loadApps();
            for (Spring s : pop) s.snap(0);
            rows = buildRows();
        }

        /** OK or → on the menu: into the right panel. */
        private void enter() {
            col = 1;
            scroll = 0;
            focus = 0;
            rows = buildRows();
            if (section == S_KEYS) HomeMode.ensureRemoteService(getContext(), 0);   // the buttons work through this service
            if (section == S_MODE) focus = freeActive() ? 0 : 1;
        }

        private List<Row> buildRows() {
            List<Row> out = new ArrayList<>();
            if (picking) {
                for (int i = 0; i < pickLabels.size(); i++) {
                    Row r = new Row(K_CHECK, "pick", pickLabels.get(i));
                    r.on = i == pickCurrent;
                    out.add(r);
                }
                return out;
            }
            switch (section) {
                case S_HOME: {
                    String lang = Prefs.language();
                    out.add(row(K_LINK, "language", Ui.tr("Language"), lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang)));
                    String theme = Prefs.theme();
                    out.add(row(K_VALUE, "theme", Ui.tr("Theme"),
                            theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark") : Ui.tr("Automatic")));
                    out.add(toggle("sounds", Ui.tr("Navigation sounds"), Prefs.sounds()));
                    out.add(row(K_VALUE, "transparency", Ui.tr("Transparency"), Prefs.transparency() + "%"));
                    break;
                }
                case S_APPS:
                    editorRows(out);
                    break;
                case S_RAIL:
                    out.add(always("home", Ui.tr("Home"), "home"));
                    editorRows(out);
                    out.add(always("settings", Ui.tr("Settings"), "settings"));
                    break;
                case S_BACKGROUND: {
                    out.add(row(K_VALUE, "background", Ui.tr("Background"),
                            "mine".equals(Prefs.background()) ? Ui.tr("My Photos") : Ui.tr("Photo of the day")));
                    int photos = Photos.files(getContext()).size();
                    out.add(row(K_VALUE, "photos", Ui.tr("My Photos"), photos == 0 ? Ui.tr("photos|None")
                            : Prefs.photos() ? Ui.plural("plural|one:{n} photo|other:{n} photos", photos) : Ui.tr("Off")));
                    out.add(row(K_VALUE, "music", Ui.tr("Music background"),
                            "album".equals(Prefs.musicBackground()) ? Ui.tr("Album cover") : Ui.tr("Artist photo")));
                    break;
                }
                case S_SAVER: {
                    Row p = row(K_ACTION, "preview", Ui.tr("Preview"), "");
                    p.symbol = "play";
                    out.add(p);
                    editorRows(out);
                    break;
                }
                case S_CLOCK: {
                    out.add(toggle("weather", Ui.tr("Weather widget"), Prefs.weather()));
                    String unit = Prefs.temperature();
                    out.add(row(K_VALUE, "temperature", Ui.tr("Temperature"),
                            unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("unit|Automatic")));
                    out.add(row(K_LINK, "city", Ui.tr("City"),
                            !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city()));
                    break;
                }
                case S_KEYS: {
                    String[] labels = keyLabels(), values = keyValues();
                    for (int i = 0; i < labels.length; i++) out.add(row(K_LINK, "key:" + i, labels[i], values[i]));
                    break;
                }
                default: {
                    boolean free = freeActive(), working = busy();
                    Row a = row(K_CHECK, "free", "Free My TV", "");
                    a.sub = Ui.tr("Turns off only the TV's own home screen");
                    a.on = free && !working;
                    if (working) a.value = busyText().isEmpty() ? Ui.tr("Starting…") : busyText();
                    out.add(a);
                    Row b = row(K_CHECK, "original", Ui.tr("Original"), "");
                    b.sub = Ui.tr("The TV's factory home screen");
                    b.on = !free && !working;
                    out.add(b);
                    Row s = row(K_ACTION, "tvsettings", Ui.tr("TV Settings"), "");
                    s.symbol = "tv_settings";
                    out.add(s);
                }
            }
            return out;
        }

        private static Row row(int kind, String key, String label, String value) {
            Row r = new Row(kind, key, label);
            r.value = value;
            return r;
        }

        private static Row toggle(String key, String label, boolean on) {
            Row r = new Row(K_TOGGLE, key, label);
            r.on = on;
            return r;
        }

        private static Row always(String key, String label, String symbol) {
            Row r = new Row(K_ALWAYS, key, label);
            r.symbol = symbol;
            r.handle = true;
            return r;
        }

        /** The editor's candidates: a handle, an icon, the name and a switch (on: in the list, in this order). */
        private void editorRows(List<Row> out) {
            for (String[] cand : shelfRows) {
                Row r = new Row(K_TOGGLE, "item:" + cand[0], cand[1]);
                r.sub = cand[2];
                r.symbol = cand[3];
                r.handle = true;
                r.on = shelfOrder.contains(cand[0]);
                if (editing == ED_SHELF) r.icon = appIcons.get(cand[0]);
                out.add(r);
            }
        }

        /** Rows before the editor's candidates (Home in Side bar, Preview in Screensaver). */
        private int editorHead() {
            return section == S_RAIL || section == S_SAVER ? 1 : 0;
        }

        private Row current() {
            int at = scroll + focus;
            return at >= 0 && at < rows.size() ? rows.get(at) : null;
        }

        private void moveFocus(int d) {
            int at = scroll + focus + d;
            if (at < 0 || at >= rows.size()) return;
            if (d < 0 && focus == 0) scroll--;
            else if (d > 0 && focus == VISIBLE - 1) scroll++;
            else focus += d;
        }

        private void ensureVisible(int at) {
            if (at < scroll) scroll = at;
            else if (at >= scroll + VISIBLE) scroll = at - VISIBLE + 1;
            focus = at - scroll;
        }

        // MARK: - Keys

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            rows = buildRows();
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN: {
                    if (!down) return true;
                    int d = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    if (col == 0) {
                        if (section + d >= 0 && section + d < SECTIONS) selectSection(section + d);
                    } else if (moving) {
                        moveItem(d);
                    } else {
                        moveFocus(d);
                    }
                    break;
                }
                case KeyEvent.KEYCODE_DPAD_LEFT: {
                    if (!down) return true;
                    Row r = current();
                    if (col == 1 && !moving && !picking && r != null && r.kind == K_VALUE) {
                        adjust(r.key, -1);
                    } else if (col == 1 && !moving && !picking) {
                        col = 0;
                    }
                    break;
                }
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    if (!down) return true;
                    Row r = current();
                    if (col == 0) enter();
                    else if (!moving && !picking && r != null && r.kind == K_VALUE) adjust(r.key, 1);
                    else if (!moving && !picking && r != null && r.kind == K_LINK) activate();
                    break;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) {
                            okDown = true;
                            held = false;
                        } else if (okDown && !held && canMove()) {
                            // Held: the row lifts (or drops), and ▲▼ move it.
                            held = true;
                            moving = !moving;
                            invalidate();
                        }
                    } else {
                        if (okDown && !held && !e.isCanceled() && spoken.keyClick()) {
                            if (moving) moving = false; else activate();
                        }
                        okDown = false;
                        held = false;
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    if (col == 0 && !picking) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (moving) moving = false;
                            else if (picking) closePicker();
                            else col = 0;
                        }
                        backDown = false;
                    }
                    break;
                default:
                    return false;
            }
            rows = buildRows();
            invalidate();
            spoken.focusMoved();
            return true;
        }

        /** The focused row can be lifted: an editor's candidate that is on. */
        private boolean canMove() {
            Row r = current();
            return col == 1 && !picking && editing != ED_NONE && r != null && r.key.startsWith("item:") && r.on;
        }

        /** OK: into the panel from the menu; in the panel, what the focused row does. */
        private void activate() {
            Context c = getContext();
            if (col == 0) {
                enter();
                invalidate();
                spoken.focusMoved();
                return;
            }
            Row r = current();
            if (r == null) return;
            if (picking) {
                pick(scroll + focus);
            } else if (r.key.startsWith("item:")) {
                toggleShelfApp(r.key.substring(5));
            } else {
                switch (r.kind) {
                    case K_TOGGLE:
                    case K_VALUE:
                        if (r.key.equals("photos") && !Photos.files(c).isEmpty()) askRemovePhotos();
                        else adjust(r.key, 1);
                        break;
                    case K_LINK:
                        if (r.key.equals("language")) openPicker(PICK_LANGUAGE);
                        else if (r.key.equals("city")) askCity();
                        else if (r.key.startsWith("key:")) openPicker(Integer.parseInt(r.key.substring(4)) + 1);
                        break;
                    case K_CHECK: {
                        boolean wantFree = r.key.equals("free");
                        boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                        if (!already && !busy()) {
                            HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                            pendingAt = SystemClock.uptimeMillis();
                        }
                        break;
                    }
                    case K_ACTION:
                        if (r.key.equals("preview")) {
                            pause();
                            host.showPreview();
                            return;
                        }
                        activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        break;
                    default:
                        break;
                }
            }
            rows = buildRows();
            invalidate();
            spoken.focusMoved();
        }

        // MARK: - Values

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};

        /** Changes a row's value one step (OK and → go forward, ← goes back). */
        private void adjust(String key, int dir) {
            switch (key) {
                case "theme": {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case "temperature": {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case "sounds": Prefs.setSounds(!Prefs.sounds()); break;
                case "transparency": {
                    // Steps of 10; OK past 100% starts again at 0%.
                    int next = Prefs.transparency() + 10 * dir;
                    Prefs.setTransparency(next > 100 ? 0 : next);
                    return;
                }
                case "weather": Prefs.setWeather(!Prefs.weather()); break;
                case "background": Prefs.setBackground("mine".equals(Prefs.background()) ? "daily" : "mine"); break;
                case "music": Prefs.setMusicBackground("album".equals(Prefs.musicBackground()) ? "artist" : "album"); break;
                case "photos":
                    if (Photos.files(getContext()).isEmpty()) return;
                    Prefs.setPhotos(!Prefs.photos());
                    return;
                default: return;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        /** "auto", then English and every language with a catalogue. */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("On") : Ui.tr("Off");
        }

        private String[] keyLabels() {
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Tabii button")};
            if (RemoteKeys.XIAOMI) return new String[] {Ui.tr("Mi button"), Ui.tr("Okko button"), Ui.tr("VK button"),
                    Ui.tr("Rutube button"), Ui.tr("KION button"), Ui.tr("Wink button"), Ui.tr("IVI button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("BRAVIA CORE button"),
                    Ui.tr("Netflix button"), Ui.tr("Disney+ button"),
                    Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Crunchyroll button"), Ui.tr("Google Play button")};
            return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                    Ui.tr("Prime Video button"), Ui.tr("TCL Channel button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(java.util.Locale.ROOT).compareTo(b[1].toLowerCase(java.util.Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony and Xiaomi, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Sony default") : RemoteKeys.XIAOMI ? Ui.tr("Xiaomi default") : Ui.tr("Nothing"));
            // On Xiaomi the Mi button is the remote's Home button: it can open this home screen.
            if (RemoteKeys.XIAOMI) {
                appPkgs.add(HomeMode.PKG);
                appLabels.add("Free My TV");
            }
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        private void askCity() {
            if (Ui.tr("Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        // MARK: - My Photos (sent from a computer: tools/photos.py)

        /** OK on My Photos: asks, then deletes them all. */
        private void askRemovePhotos() {
            int n = Photos.files(getContext()).size();
            if (n == 0) return;
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.plural("plural|one:Remove {n} photo?|other:Remove {n} photos?", n))
                    .setPositiveButton(Ui.tr("Remove"), (d, which) -> {
                        Photos.removeAll(getContext());
                        invalidate();
                        spoken.focusMoved();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();   // one stray OK keeps them
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.trf("Couldn't search now: {error}", "error", e.getMessage());
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && section == S_CLOCK && col == 1) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        // MARK: - Pickers (inside the right panel)

        private void openPicker(int which) {
            pickFor = which;
            pickReturn = scroll + focus;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else if (which == PICK_LANGUAGE) {
                String[] choices = languageChoices();
                for (String code : choices) pickLabels.add(code.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(code));
                pickCurrent = java.util.Arrays.asList(choices).indexOf(Prefs.language());
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            picking = true;
            int at = Math.max(0, pickCurrent);
            scroll = Math.max(0, Math.min(at - 3, pickLabels.size() - VISIBLE));
            focus = at - scroll;
            for (Spring s : pop) s.snap(0);
        }

        private void pick(int at) {
            if (pickFor == PICK_CITY) {
                String[] city = cities.get(at);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(getContext()).settingsChanged();
            } else if (pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[at]);
            } else {
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
            }
            closePicker();
        }

        private void closePicker() {
            picking = false;
            scroll = 0;
            ensureVisible(pickReturn);
            for (Spring s : pop) s.snap(0);
        }

        private String pickerTitle() {
            return pickFor == PICK_CITY ? Ui.tr("City") : pickFor == PICK_LANGUAGE ? Ui.tr("Language") : keyLabels()[pickFor - 1];
        }

        // MARK: - List editors (Apps, Side bar, Screensaver)

        /** The side bar now and everything it can hold (inputs included: slow, so once per visit). */
        private void loadRail() {
            railByKey.clear();
            for (HomeApps.Item it : HomeApps.railCandidates(getContext())) railByKey.put(it.key, it);
            railKeys.clear();
            railKeys.addAll(HomeApps.railKeys(getContext()));
        }

        private void openEditor(int which) {
            shelfOrder.clear();
            List<String[]> cands = new ArrayList<>();
            if (which == ED_RAIL) {
                // Home and Settings always stay, first and last: only what goes between them is edited.
                for (String key : railKeys) if (!key.equals("home") && !key.equals("settings")) shelfOrder.add(key);
                for (HomeApps.Item it : railByKey.values()) {
                    if (it.key.equals("home") || it.key.equals("settings")) continue;
                    cands.add(new String[] {it.key, it.label, it.sub == null ? "" : it.sub, it.symbol != null ? it.symbol : "hdmi"});
                }
                // railByKey has no order: the candidates in the side bar's own order.
                List<String> order = new ArrayList<>();
                for (HomeApps.Item it : HomeApps.railCandidates(getContext())) order.add(it.key);
                Collections.sort(cands, (a, b) -> order.indexOf(a[0]) - order.indexOf(b[0]));
            } else if (which == ED_SCENES) {
                shelfOrder.addAll(Prefs.scenes());
                for (String[] scene : Videos.SCENES) cands.add(new String[] {scene[0], Videos.sceneName(scene[0]), "", null});
            } else {
                // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the
                // limit) without a row to take it off.
                android.content.pm.PackageManager pm = getContext().getPackageManager();
                for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
                for (String[] app : HomeApps.shelfCandidates(getContext())) cands.add(new String[] {app[0], app[1], "", null});
                loadAppIcons(cands);
            }
            shelfRows.clear();
            for (String key : shelfOrder) {
                for (String[] cand : cands) if (cand[0].equals(key)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : cands) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** The apps' icons for the Apps rows, rounded, made in the background (once each). */
        private void loadAppIcons(List<String[]> cands) {
            final Context c = getContext().getApplicationContext();
            final List<String> todo = new ArrayList<>();
            for (String[] cand : cands) if (!appIcons.containsKey(cand[0])) todo.add(cand[0]);
            if (todo.isEmpty()) return;
            final int size = Math.round(68 * k);
            new Thread(() -> {
                for (String pkg : todo) {
                    Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(b);
                    Path round = new Path();
                    round.addRoundRect(new RectF(0, 0, size, size), size * 0.24f, size * 0.24f, Path.Direction.CW);
                    canvas.clipPath(round);
                    canvas.drawColor(0xFF2A2C33);
                    Bitmap store = HomeApps.storeIcon(c, pkg);
                    if (store != null) {
                        canvas.drawBitmap(store, null, new RectF(0, 0, size, size), new Paint(Paint.FILTER_BITMAP_FLAG));
                        store.recycle();
                    } else {
                        Drawable d = HomeApps.appIcon(c, pkg);
                        if (d != null) {
                            d.setBounds(0, 0, size, size);
                            d.draw(canvas);
                        }
                    }
                    handler.post(() -> {
                        appIcons.put(pkg, b);
                        invalidate();
                    });
                }
            }).start();
        }

        /** Same rows after a change: keeps the focus on the item that was focused, and saves. */
        private void refreshShelfRows(String key) {
            List<String[]> all = new ArrayList<>(shelfRows);
            shelfRows.clear();
            for (String inOrder : shelfOrder) {
                for (String[] cand : all) if (cand[0].equals(inOrder)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : all) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (shelfRows.get(i)[0].equals(key)) ensureVisible(editorHead() + i);
            }
            if (editing == ED_RAIL) {
                Prefs.setRail(shelfOrder);
                railKeys.clear();
                railKeys.add("home");
                railKeys.addAll(shelfOrder);
                railKeys.add("settings");
            } else if (editing == ED_SCENES) {
                Prefs.setScenes(shelfOrder);
            } else {
                Prefs.setShelf(shelfOrder);
            }
        }

        /** OK: shows the item (at the end of the list) or hides it. */
        private void toggleShelfApp(String key) {
            if (shelfOrder.contains(key)) shelfOrder.remove(key);
            else if (editing != ED_SHELF || shelfOrder.size() < HomeApps.maxShelf(getContext())) shelfOrder.add(key);
            else return;
            refreshShelfRows(key);
        }

        /** ▲▼ while lifted: one place earlier or later. */
        private void moveItem(int dir) {
            Row r = current();
            if (r == null || !r.key.startsWith("item:")) return;
            String key = r.key.substring(5);
            int at = shelfOrder.indexOf(key), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, key);
            refreshShelfRows(key);
        }

        // MARK: - Drawing

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            rows = buildRows();
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = col == 1 && i == focus && i < visibleRows() ? 1 : 0;
                pop[i].step(dt);
            }
            for (int i = 0; i < menuPop.length; i++) {
                menuPop[i].target = col == 0 && i == section ? 1 : 0;
                menuPop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            boolean over = host.overHome();
            if (!over) {
                // Their own screen: the sky, darker at the top and the bottom, and a copy of the side bar.
                if (up - lastSky >= 100) {
                    lastSky = up;
                    scene.advance(seconds);
                }
                scene.drawBackground(c);
                fill.setShader(dim);
                c.drawRect(0, 0, w, h, fill);
                fill.setShader(null);
                drawRail(c, 78 * k, 460 * k, railKeys);
            }
            // Over the home screen: nothing behind is touched; the panels sit right beside its side bar.
            int save = c.save();
            if (over) c.translate(-OVER_SHIFT * k, 0);
            c.drawText(Ui.tr("Settings"), 245 * k, 142 * k, pageTitle);
            drawMenu(c);
            drawPane(c, seconds);
            c.drawText(versionName(getContext()), (MENU_L + 30) * k, (MENU_B + 52) * k, version);
            c.restoreToCount(save);
        }

        /** Over the home screen the panels move this far left, next to its side bar (1080p pixels). */
        private static final float OVER_SHIFT = 80;

        /**
         * A panel as on the home screen (see-through, Transparency in Home screen), a little darker for the text. The
         * fill's alpha is set here every time: a shader paint takes the paint's alpha, whatever was drawn before.
         */
        private void drawGlassPanel(Canvas c, RectF r, float radius) {
            glass.opacity = 1 - Prefs.transparency() / 100f;
            glass.draw(c, r, radius, null, null, 0, w, h, 1);
            fill.setShader(null);
            fill.setColor(host.overHome() ? 0x29000000 : 0x1FFFFFFF);
            c.drawRoundRect(r, radius, radius, fill);
        }

        private void drawMenu(Canvas c) {
            pill.set(MENU_L * k, PANEL_T * k, MENU_R * k, MENU_B * k);
            drawGlassPanel(c, pill, RADIUS * k);
            for (int i = 0; i < SECTIONS; i++) {
                RectF r = menuBox(i);
                float f = Ui.clamp(menuPop[i].value);
                if (i == section || f > 0.01f) {
                    // The chosen part: a lighter glass; while the menu has the focus, white.
                    fill.setColor(blend(0x2EFFFFFF, WHITE, f));
                    c.drawRoundRect(r, 20 * k, 20 * k, fill);
                }
                int ink = blend(0xF2FFFFFF, INK, f);
                Symbols.draw(getContext(), c, SECTION_SYMBOLS[i], r.left + 34 * k, r.centerY(), 28 * k, ink);
                menuLabel.setColor(ink);
                c.drawText(sectionName(i), r.left + 66 * k, r.centerY() + Ui.halfCap(menuLabel), menuLabel);
                float cw = Symbols.width(getContext(), "chevron_right", 18 * k);
                Symbols.draw(getContext(), c, "chevron_right", r.right - 24 * k - cw / 2, r.centerY(), 18 * k,
                        blend(0x73FFFFFF, 0x991C1C1E, f));
            }
        }

        private void drawPane(Canvas c, float seconds) {
            pill.set(PANE_L * k, PANEL_T * k, PANE_R * k, PANE_B * k);
            drawGlassPanel(c, pill, RADIUS * k);
            float x = (PANE_L + 39) * k;
            c.drawText(picking ? pickerTitle() : sectionName(section), x, 260 * k, paneTitle);
            c.drawText(picking ? Ui.tr("OK picks, Back cancels.") : sectionSub(section), x, 297 * k, paneSub);
            int n = visibleRows();
            for (int i = 0; i < n; i++) drawRow(c, rows.get(scroll + i), i, seconds);
            if (rows.size() > VISIBLE) {
                small.setColor(0x73FFFFFF);
                c.drawText((scroll + focus + 1) + " / " + rows.size(), rowRight() * k - 38 * k, HINT_Y * k, small);
            }
            if (section == S_MODE && !picking) drawModeInfo(c, (ROWS_T + rows.size() * ROW_PITCH + 40) * k);
            if (section == S_RAIL && !picking) drawRail(c, 1495 * k, 640 * k, railKeys);
            drawHints(c);
        }

        private void drawRow(Canvas c, Row r, int i, float seconds) {
            RectF b = rowBox(i);
            rowRect.set(b);
            boolean lifted = moving && i == focus;
            float f = drawRowBackground(c, i, lifted);
            float cy = rowRect.centerY();
            float x = rowRect.left + 22 * k;
            if (r.handle) {
                drawHandle(c, x, cy, lifted ? 1 : 0.35f + 0.1f * f, f);
                x += 50 * k;
            } else {
                x += 8 * k;
            }
            int ink = blend(0xF2FFFFFF, INK, f);
            if (r.icon != null) {
                tmp.set(x - 4 * k, cy - 19 * k, x + 34 * k, cy + 19 * k);
                c.drawBitmap(r.icon, null, tmp, image);
                x += 48 * k;
            } else if (r.symbol != null) {
                drawSymbol(c, r.symbol, x + 13 * k, cy, 26 * k, ink);
                x += 44 * k;
            }
            float right = rowRect.right - 38 * k;
            rowLabel.setColor(ink);
            float base = cy + Ui.halfCap(rowLabel);
            String label = TextUtils.ellipsize(r.label, new TextPaint(rowLabel), Math.max(80 * k, right - x - 260 * k),
                    TextUtils.TruncateAt.END).toString();
            c.drawText(label, x, base, rowLabel);
            if (!r.sub.isEmpty()) {
                rowSub.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                c.drawText(r.sub, x + rowLabel.measureText(label) + 22 * k, base, rowSub);
            }
            rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
            switch (r.kind) {
                case K_TOGGLE:
                    drawSwitch(c, right, cy, r.on, f);
                    if (lifted) drawArrows(c, right - 92 * k, cy, ink);
                    break;
                case K_VALUE:
                    c.drawText(r.value, right, base, rowValue);
                    break;
                case K_LINK:
                case K_ACTION: {
                    float cw = Symbols.width(getContext(), "chevron_right", 18 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, cy, 18 * k, blend(0x73FFFFFF, 0x991C1C1E, f));
                    if (!r.value.isEmpty()) {
                        String v = TextUtils.ellipsize(r.value, new TextPaint(rowValue), 420 * k, TextUtils.TruncateAt.END).toString();
                        c.drawText(v, right - cw - 16 * k, base, rowValue);
                    }
                    break;
                }
                case K_ALWAYS:
                    small.setColor(blend(0x73FFFFFF, 0x731C1C1E, f));
                    c.drawText(Ui.tr("Always"), right, base, small);
                    break;
                case K_CHECK: {
                    float cw = Symbols.width(getContext(), "check", 24 * k);
                    if (r.on) Symbols.draw(getContext(), c, "check", right - cw / 2, cy, 24 * k, ink);
                    if (!r.value.isEmpty()) {
                        // Switching: the current step, with the spinning arc.
                        String v = TextUtils.ellipsize(r.value, new TextPaint(rowValue), 440 * k, TextUtils.TruncateAt.END).toString();
                        c.drawText(v, right, base, rowValue);
                        spinner.setColor(ink);
                        Ui.spinner(c, right - rowValue.measureText(v) - 24 * k, cy, 11 * k, seconds * 1.3f, spinner);
                    }
                    break;
                }
                default:
                    break;
            }
        }

        /** No background; focused, white with a soft shadow. Lifted (moving), a little larger. Returns the focus. */
        private float drawRowBackground(Canvas c, int i, boolean lifted) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + (lifted ? 0.035f : 0.015f) * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, (lifted ? 14 : 8) * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, (lifted ? 0.55f : 0.35f) * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -(lifted ? 14 : 8) * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        /** ⋮⋮: two columns of three dots, "this row can move". */
        private void drawHandle(Canvas c, float x, float cy, float strength, float f) {
            fill.setColor(Ui.alpha(blend(0xFFFFFFFF, INK, f), strength));
            for (int col = 0; col < 2; col++) {
                for (int row = -1; row <= 1; row++) c.drawCircle(x + col * 12 * k, cy + row * 9 * k, 2.2f * k, fill);
            }
        }

        /** ▲▼ beside a lifted row. */
        private void drawArrows(Canvas c, float cx, float cy, int color) {
            fill.setColor(color);
            Path p = new Path();
            p.moveTo(cx - 8 * k, cy - 4 * k);
            p.lineTo(cx + 8 * k, cy - 4 * k);
            p.lineTo(cx, cy - 15 * k);
            p.close();
            p.moveTo(cx - 8 * k, cy + 4 * k);
            p.lineTo(cx + 8 * k, cy + 4 * k);
            p.lineTo(cx, cy + 15 * k);
            p.close();
            c.drawPath(p, fill);
        }

        /** An iOS-style switch, its right edge at `right`: green when on. */
        private void drawSwitch(Canvas c, float right, float cy, boolean on, float f) {
            tmp.set(right - 62 * k, cy - 18 * k, right, cy + 18 * k);
            fill.setColor(on ? GREEN : blend(0x3DFFFFFF, 0x2E1C1C1E, f));
            c.drawRoundRect(tmp, 18 * k, 18 * k, fill);
            float kx = on ? right - 18 * k : right - 44 * k;
            fill.setColor(0x33000000);
            c.drawCircle(kx, cy + 1.5f * k, 15.5f * k, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(kx, cy, 15 * k, fill);
        }

        /** The keys at the bottom of the panel ("OK show / hide", "Hold OK move, then ▲▼"), then a note. */
        private void drawHints(Canvas c) {
            String[] pairs;
            String note = "";
            Row r = current();
            if (col == 0) pairs = new String[] {"OK", Ui.tr("open")};
            else if (picking) pairs = new String[] {"OK", Ui.tr("choose")};
            else if (moving) pairs = new String[] {"▲ ▼", Ui.tr("move"), "OK", Ui.tr("done")};
            else if (r == null || r.kind == K_ALWAYS) pairs = new String[0];
            else if (r.key.startsWith("item:")) pairs = new String[] {"OK", Ui.tr("show / hide"), Ui.tr("Hold OK"), Ui.tr("move, then ▲▼")};
            else if (r.kind == K_VALUE) pairs = new String[] {"◀ ▶", Ui.tr("change")};
            else if (r.kind == K_TOGGLE) pairs = new String[] {"OK", Ui.tr("change")};
            else if (r.kind == K_CHECK) pairs = new String[] {"OK", Ui.tr("choose")};
            else pairs = new String[] {"OK", Ui.tr("open")};
            if (col == 1 && !picking && r != null) {
                if (r.key.equals("photos")) {
                    note = Photos.files(getContext()).isEmpty() ? Ui.tr("Send them from a computer: see “Your own photos” in the README.")
                            : Ui.tr("OK removes them; the arrows turn them on or off.");
                } else if (section == S_APPS && HomeApps.lowRam(getContext())) {
                    note = Ui.tr("This TV has little memory, so the shelf holds fewer apps.");
                } else if (section == S_SAVER && r.key.startsWith("item:")) {
                    note = Ui.tr("Each scene downloads once, on Wi-Fi or Ethernet.");
                } else if (r.kind == K_CHECK) {
                    note = Ui.tr("Switching takes a few seconds; the screen blinks while it measures.");
                }
            }
            float x = (PANE_L + 39) * k, base = HINT_Y * k;
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                float cw = chipText.measureText(pairs[i]) + 20 * k;
                tmp.set(x, base - 27 * k, x + cw, base + 9 * k);
                fill.setColor(0x2EFFFFFF);
                c.drawRoundRect(tmp, 8 * k, 8 * k, fill);
                c.drawText(pairs[i], x + 10 * k, base, chipText);
                x += cw + 10 * k;
                c.drawText(pairs[i + 1], x, base, hintText);
                x += hintText.measureText(pairs[i + 1]) + 26 * k;
            }
            if (!note.isEmpty()) {
                float room = (rowRight() - 120) * k - x;
                if (room > 120 * k) {
                    c.drawText(TextUtils.ellipsize(note, new TextPaint(hintText), room, TextUtils.TruncateAt.END).toString(), x, base, hintText);
                }
            }
        }

        /** The side bar as the home screen shows it: a glass pill with the logo, the icons and Settings last. */
        private void drawRail(Canvas c, float cx, float cy, List<String> keys) {
            if (keys.isEmpty()) return;
            float pitch = 62 * k;
            float height = 20 * k + (logo != null ? 66 * k : 0) + keys.size() * pitch + 14 * k;
            pill.set(cx - 38 * k, cy - height / 2, cx + 38 * k, cy + height / 2);
            drawGlassPanel(c, pill, 38 * k);
            float y = pill.top + 10 * k;
            if (logo != null) {
                float lw = 50 * k, lh = lw * logo.getHeight() / logo.getWidth();
                tmp.set(cx - lw / 2, y + (52 * k - lh) / 2, cx + lw / 2, y + (52 * k + lh) / 2);
                image.setAlpha(242);
                c.drawBitmap(logo, null, tmp, image);
                image.setAlpha(255);
                y += 52 * k;
                railSeparator(c, cx, y + 7 * k);
                y += 14 * k;
            }
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                if (i == keys.size() - 1) {
                    railSeparator(c, cx, y + 7 * k);
                    y += 14 * k;
                }
                HomeApps.Item it = railByKey.get(key);
                boolean input = it != null && it.kind == HomeApps.INPUT;
                int color = !input || it.connected ? 0xFFFFFFFF : 0x73FFFFFF;
                float icy = y + pitch / 2 - 3 * k;
                drawSymbol(c, key.equals("home") ? "home" : key.equals("settings") ? "settings"
                        : it != null && it.symbol != null ? it.symbol : "hdmi", cx, icy, 26 * k, color);
                if (input && it.connected) {
                    fill.setColor(GREEN);
                    c.drawCircle(cx + 16 * k, icy - 15 * k, 4.5f * k, fill);
                }
                y += pitch;
            }
        }

        private void railSeparator(Canvas c, float cx, float y) {
            fill.setColor(0x40FFFFFF);
            c.drawRect(cx - 14 * k, y - 0.75f * k, cx + 14 * k, y + 0.75f * k, fill);
        }

        /** A symbol by name; Wi-Fi is drawn (three arcs and a dot), as on the home screen. */
        private void drawSymbol(Canvas c, String name, float cx, float cy, float size, int color) {
            if (!"wifi".equals(name)) {
                Symbols.draw(getContext(), c, name, cx, cy, size, color);
                return;
            }
            float r = size * 0.46f;
            arc.setStrokeWidth(r * 0.24f);
            arc.setColor(color);
            for (int i = 1; i <= 3; i++) {
                float rr = r * (0.42f + 0.38f * (i - 1)) * 1.15f;
                tmp.set(cx - rr, cy + 4 * k - rr, cx + rr, cy + 4 * k + rr);
                c.drawArc(tmp, 225, 90, false, arc);
            }
            fill.setColor(color);
            c.drawCircle(cx, cy + 4 * k - r * 0.05f, r * 0.16f, fill);
        }

        /** Free My TV mode, under its rows: what the switch asks for or how it went, the latest notice, the picks. */
        private void drawModeInfo(Canvas c, float y) {
            float x = (ROW_L + 30) * k, width = (ROW_R - ROW_L - 60) * k;
            boolean working = busy();
            String error = state.optString("error");
            String line = working && busyText().equals(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."))
                    ? Ui.tr("On the TV: tick “Always allow” and press OK.") : !working ? error : "";
            if (!line.isEmpty()) {
                c.drawText(TextUtils.ellipsize(line, new TextPaint(warn), width, TextUtils.TruncateAt.END).toString(), x, y, warn);
                y += 52 * k;
            }
            c.drawText(Ui.tr("NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            JSONObject o = notices == null || notices.length() == 0 ? null : notices.optJSONObject(0);
            String last = o == null ? Ui.tr("None yet.") : o.optString("text");
            c.drawText(TextUtils.ellipsize(last, notice, width, TextUtils.TruncateAt.END).toString(), x, y + 36 * k, notice);
            drawRecs(c, x, width, y + 96 * k);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float x, float width, float y) {
            c.drawText(Ui.tr("RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("No TMDB key: see the README (config.properties).");
            else if (error.equals(Recs.NO_KNOWN_APPS)) line = Ui.tr("None of the TV's streaming apps can give picks.");
            else if (error.equals(Recs.NO_APPS)) line = Ui.trf("TMDB has none of the TV's apps for tmdb_region={region}.", "region", Recs.REGION);
            else if (!error.isEmpty()) line = Ui.trf("Error: {error}.", "error", error);
            else if (count > 0) line = Ui.trf("On · {n} titles from the TV's apps, 3 new ones every hour.", "n", count);
            else line = Ui.tr("Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, width, TextUtils.TruncateAt.END).toString(), x, y + 36 * k, notice);
            String credit = Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB.");
            if (!Config.WATCHMODE_KEY.trim().isEmpty()) credit += " " + Ui.tr("Links to the titles by Watchmode.");
            c.drawText(TextUtils.ellipsize(credit, new TextPaint(version), width, TextUtils.TruncateAt.END).toString(), x, y + 66 * k, version);
            return y + 66 * k;
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String versionName(Context c) {
            try {
                return "Free My TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Free My TV";
            }
        }
    }
}
