package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The home screen's photos of the day: a new batch every day, today's photo from Bing's regions (landscapes in 4K,
 * 3840×2160), fetched just after midnight. The first shows; "Another photo" moves to the next of today's, for today
 * only. The images are saved in the app's cache, so the home screen opens with one even offline.
 */
final class DailyPhoto {
    private DailyPhoto() {}

    static final class Photo {
        final String title, place;
        final File file;

        Photo(String title, String place, File file) {
            this.title = title;
            this.place = place;
            this.file = file;
        }
    }

    /**
     * A fresh batch every day: today's photo of Bing's regions (they usually differ; Europe's change at about our
     * midnight, the others through the day). Their feed, newest first: idx=0, n=1 is the region's photo of the day.
     */
    private static final String FEED = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=";
    /** Photos in a day's batch: today's from every region first, then the last days' (regions often share one). */
    private static final int BATCH = 16;
    private static final String[] MARKETS = {"en-GB", "de-DE", "fr-FR", "it-IT", "es-ES", "en-US", "ja-JP", "en-IN",
            "en-CA", "en-AU", "pt-BR", "zh-CN", "nl-NL", "sv-SE"};
    /** Within a day, looked at again every 2 hours (regions further west change later). */
    private static final long EVERY = 2 * 3600_000L;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("daily-photo", Context.MODE_PRIVATE);
    }

    private static File dir(Context c) {
        return new File(c.getCacheDir(), "daily");
    }

    static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    /** The day of the batch on the TV ("" before the first). */
    static String batchDay(Context c) {
        return sp(c).getString("day", "");
    }

    /** The feed's entries as {title, place, url, file name}, the newest first (empty before the first fetch). */
    private static List<String[]> entries(Context c) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("feed", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[] {o.optString("title"), o.optString("place"), o.optString("url"), o.optString("file")});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Today's photo (or the one picked for today), if it is on the TV; null otherwise. Any thread. */
    static Photo current(Context c) {
        List<String[]> all = entries(c);
        if (all.isEmpty()) return null;
        int pick = sp(c).getString("pickDay", "").equals(today()) ? sp(c).getInt("pick", 0) : 0;
        for (int n = 0; n < all.size(); n++) {
            String[] e = all.get((pick + n) % all.size());
            File f = new File(dir(c), e[3]);
            if (f.isFile()) return new Photo(e[0], e[1], f);
        }
        return null;
    }

    /** Today's photos as {title, place, "1" if on the TV}, in order (Settings › Background › Today's photos). */
    static List<String[]> list(Context c) {
        List<String[]> out = new ArrayList<>();
        for (String[] e : entries(c)) out.add(new String[] {e[0], e[1], new File(dir(c), e[3]).isFile() ? "1" : ""});
        return out;
    }

    /** The batch's photo shown now (its index in list()). */
    static int picked(Context c) {
        return sp(c).getString("pickDay", "").equals(today()) ? sp(c).getInt("pick", 0) : 0;
    }

    /** Shows photo `i` of the batch today (if it's on the TV). */
    static void choose(Context c, int i) {
        List<String[]> all = entries(c);
        if (i < 0 || i >= all.size() || !new File(dir(c), all.get(i)[3]).isFile()) return;
        sp(c).edit().putString("pickDay", today()).putInt("pick", i).apply();
    }

    /** The photo `step` after the one shown (on the TV), without choosing it: to prepare it ahead. */
    static Photo peek(Context c, int step) {
        List<String[]> all = entries(c);
        if (all.isEmpty()) return null;
        int pick = picked(c);
        for (int n = 1; n <= all.size(); n++) {
            String[] e = all.get(Math.floorMod(pick + step * n, all.size()));
            File f = new File(dir(c), e[3]);
            if (f.isFile()) return new Photo(e[0], e[1], f);
        }
        return null;
    }

    /** "Another photo today": the next of the last 8 days' photos that is on the TV. */
    static void next(Context c, int dir) {
        List<String[]> all = entries(c);
        if (all.isEmpty()) return;
        SharedPreferences p = sp(c);
        int pick = p.getString("pickDay", "").equals(today()) ? p.getInt("pick", 0) : 0;
        for (int n = 1; n <= all.size(); n++) {
            int i = Math.floorMod(pick + dir * n, all.size());
            if (new File(dir(c), all.get(i)[3]).isFile()) {
                p.edit().putString("pickDay", today()).putInt("pick", i).apply();
                return;
            }
        }
    }

    /**
     * Fetches today's batch and the images not on the TV yet: at once on a new day (just after midnight), else at
     * most every 2 hours. Returns true if the photo to show changed. Background thread.
     */
    static boolean refresh(Context c) {
        return refresh(c, false);
    }

    /** `force`: now, whatever the time of the last look (Settings › Background › Refresh photos). */
    static synchronized boolean refresh(Context c, boolean force) {
        SharedPreferences p = sp(c);
        long now = System.currentTimeMillis(), at = p.getLong("at", 0);
        boolean newDay = !today().equals(p.getString("day", ""));
        if (!force && !newDay && now - at < EVERY && now >= at && !entries(c).isEmpty()) return false;
        try {
            JSONArray out = new JSONArray();
            File d = dir(c);
            if (!d.isDirectory() && !d.mkdirs()) return false;
            List<String> keep = new ArrayList<>();
            // Every region's last 8 days, newest first; the batch takes today's of all of them, then yesterday's...
            List<JSONArray> feeds = new ArrayList<>();
            for (String market : MARKETS) {
                try {
                    feeds.add(new JSONObject(new String(get(FEED + market), StandardCharsets.UTF_8)).getJSONArray("images"));
                } catch (Exception e) {
                    // one region down: the others still make the batch
                }
            }
            for (int day = 0; day < 8 && keep.size() < BATCH; day++) {
                for (JSONArray images : feeds) {
                    if (day >= images.length() || keep.size() >= BATCH) continue;
                    JSONObject img = images.getJSONObject(day);
                    String base = img.optString("urlbase");
                    if (base.isEmpty()) continue;
                    // The same photo in several regions has one name (its OHR id, before the region's suffix).
                    String id = base.replaceAll("^.*OHR\\.", "").replaceAll("_[A-Z]{2}-[A-Z]{2}\\d*$", "");
                    String name = id.replaceAll("[^A-Za-z0-9_.-]", "") + ".jpg";
                    if (keep.contains(name)) continue;
                    String copyright = img.optString("copyright");
                    int paren = copyright.indexOf(" (©");
                    String place = paren > 0 ? copyright.substring(0, paren) : copyright;
                    out.put(new JSONObject().put("title", img.optString("title", place)).put("place", place)
                            .put("url", "https://www.bing.com" + base + "_UHD.jpg").put("file", name));
                    keep.add(name);
                }
            }
            if (out.length() == 0) return false;
            boolean changed = newDay || out.length() != entries(c).size();
            p.edit().putString("feed", out.toString()).putLong("at", now).putString("day", today()).apply();
            boolean any = false;
            for (String[] e : entries(c)) {
                File f = new File(d, e[3]);
                if (f.isFile()) continue;
                try {
                    byte[] bytes = get(e[2]);
                    File part = new File(d, e[3] + ".part");
                    try (FileOutputStream os = new FileOutputStream(part)) {
                        os.write(bytes);
                    }
                    any |= part.renameTo(f);
                } catch (IOException ex) {
                    AppLog.w("daily photo: " + ex.getMessage());
                }
            }
            File[] old = d.listFiles();
            if (old != null) for (File f : old) if (!keep.contains(f.getName())) f.delete();
            return any || changed;
        } catch (Exception e) {
            AppLog.w("daily photo feed: " + e.getMessage());
            return false;
        }
    }

    static byte[] get(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10_000);
        con.setReadTimeout(20_000);
        con.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 30_000_000) throw new IOException("too large");
                }
                return out.toByteArray();
            }
        } finally {
            con.disconnect();
        }
    }
}
