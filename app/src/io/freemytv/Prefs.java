package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** Vestel's Netflix button: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_VESTEL_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    static final int[] KEYS = RemoteKeys.VESTEL ? new int[] {KEY_VESTEL_NETFLIX}
            : new int[] {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt", "en", "es", "de" or "ru". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("ru") ? "ru" : v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("de") ? "de"
                : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - Screensaver

    /** Its photos: "shows" (the movies and shows picked for you), "nature" or "architecture" (Scenery). */
    static String saverPhotos() {
        String v = sp().getString("saverPhotos", "shows");
        return v.equals("nature") || v.equals("architecture") ? v : "shows";
    }

    static void setSaverPhotos(String v) { put("saverPhotos", v); }

    /** Its clock's size: "small", "medium" or "large". */
    static String clockSize() {
        String v = sp().getString("clockSize", "small");
        return v.equals("medium") || v.equals("large") ? v : "small";
    }

    static void setClockSize(String v) { put("clockSize", v); }

    // MARK: - Liquid Glass

    // The look (the Liquid Glass panel on the home screen): changes show live, so they don't count as `version`
    // changes (those make the home screen start over)
    static volatile int lookVersion;

    /** How glassy the glass is, 0 to 100: clearer, bending more, brighter highlights, a lighter background in it. */
    static int glass() {
        return Math.max(0, Math.min(100, sp().getInt("glass", 70)));
    }

    static void setGlass(int v) {
        sp().edit().putInt("glass", Math.max(0, Math.min(100, v))).apply();
        lookVersion++;
    }

    /** The background's brightness, -50 (darker) to 50 (lighter); 0 as it was made. */
    static int brightness() {
        return Math.max(-50, Math.min(50, sp().getInt("brightness", 0)));
    }

    static void setBrightness(int v) {
        sp().edit().putInt("brightness", Math.max(-50, Math.min(50, v))).apply();
        lookVersion++;
    }

    /** How blurred the background is, 0 (lightly) to 100 (fully, as it always was). */
    static int blur() {
        return Math.max(0, Math.min(100, sp().getInt("blur", 100)));
    }

    static void setBlur(int v) {
        sp().edit().putInt("blur", Math.max(0, Math.min(100, v))).apply();
        lookVersion++;
    }

    private static float glassAmount = -1, brightnessAmount, blurAmount;
    private static int lookFor = -1;

    /** The look as fractions, read again only after a change (the glass and the background ask on every frame). */
    private static void look() {
        if (lookFor == lookVersion && glassAmount >= 0) return;
        lookFor = lookVersion;
        glassAmount = glass() / 100f;
        brightnessAmount = brightness() / 100f;
        blurAmount = blur() / 100f;
    }

    /** glass() as 0 to 1. */
    static float glassAmount() {
        look();
        return glassAmount;
    }

    /** brightness() as -0.5 to 0.5. */
    static float brightnessAmount() {
        look();
        return brightnessAmount;
    }

    /** blur() as 0 to 1. */
    static float blurAmount() {
        look();
        return blurAmount;
    }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: case KEY_VESTEL_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return providerTv();
            default: return "";
        }
    }

    /**
     * The channel button: the TV provider's app (EON) when installed (live TV comes from it, not the antenna), else
     * nothing (the TV opens its own TCL Channel).
     */
    private static String providerTv() {
        android.content.pm.PackageManager pm = app.getPackageManager();
        for (String pkg : HomeApps.PROVIDER_TV_APPS) {
            if (pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null) return pkg;
        }
        return "";
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    private static long keysStamp = Long.MIN_VALUE;

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            File f = keysFile();
            long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
            if (stamp != keysStamp) {
                keysStamp = stamp;
                java.util.Arrays.fill(keyApps, null);
                if (stamp != 0) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            for (int i = 0; i < KEYS.length; i++) {
                                String prefix = KEYS[i] + "=";
                                if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    static void setKeyApp(int keyCode, String pkg) {
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(key == keyCode ? pkg : keyApp(key)).append('\n');
        if (writeKeys(sb.toString())) version++;
    }
}
