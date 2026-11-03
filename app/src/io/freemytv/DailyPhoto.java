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
 * The home screen's photo of the day: Bing's (a landscape in 4K, 3840×2160, with its title and place), the last 8 days,
 * fetched once a day. Today's is the first; "Another photo today" moves to the next of those 8, for today only.
 * The images are saved in the app's cache, so the home screen opens with one even offline.
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

    private static final String FEED = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US";
    private static final long EVERY = 3 * 3600_000L;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("daily-photo", Context.MODE_PRIVATE);
    }

    private static File dir(Context c) {
        return new File(c.getCacheDir(), "daily");
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
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

    /** Fetches the feed and the images not on the TV yet (at most every 3 hours). Background thread. */
    static boolean refresh(Context c) {
        SharedPreferences p = sp(c);
        long now = System.currentTimeMillis(), at = p.getLong("at", 0);
        if (now - at < EVERY && now >= at && !entries(c).isEmpty()) return false;
        try {
            JSONObject feed = new JSONObject(new String(get(FEED), StandardCharsets.UTF_8));
            JSONArray images = feed.getJSONArray("images"), out = new JSONArray();
            File d = dir(c);
            if (!d.isDirectory() && !d.mkdirs()) return false;
            List<String> keep = new ArrayList<>();
            for (int i = 0; i < images.length(); i++) {
                JSONObject img = images.getJSONObject(i);
                String base = img.optString("urlbase");
                if (base.isEmpty()) continue;
                String name = base.replaceAll("[^A-Za-z0-9_.-]", "") + ".jpg";
                String copyright = img.optString("copyright");
                int paren = copyright.indexOf(" (©");
                String place = paren > 0 ? copyright.substring(0, paren) : copyright;
                out.put(new JSONObject().put("title", img.optString("title", place)).put("place", place)
                        .put("url", "https://www.bing.com" + base + "_UHD.jpg").put("file", name));
                keep.add(name);
            }
            p.edit().putString("feed", out.toString()).putLong("at", now).apply();
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
            return any;
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
