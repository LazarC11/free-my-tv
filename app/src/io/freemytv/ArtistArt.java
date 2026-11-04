package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The artist's photo behind the music: TheAudioDB's wide picture (1280×720, its free key), else Deezer's square
 * one; saved once per artist, so the same artist shows at once the next time. Background thread.
 */
final class ArtistArt {
    private ArtistArt() {}

    private static File dir(Context c) {
        return new File(c.getCacheDir(), "artists");
    }

    /** "Ana Nikolić, Saša Matić" → "Ana Nikolić"; "X feat. Y" → "X". */
    static String mainArtist(String artist) {
        if (artist == null) return "";
        String a = artist.split(" · ")[0];
        for (String cut : new String[] {",", " & ", " feat", " ft.", " x ", " X ", " / "}) {
            int i = a.toLowerCase(Locale.ROOT).indexOf(cut.toLowerCase(Locale.ROOT));
            if (i > 0) a = a.substring(0, i);
        }
        return a.trim();
    }

    /** The saved photo only (no download), or null. */
    static File cached(Context c, String artist) {
        String name = mainArtist(artist);
        if (name.isEmpty()) return null;
        File f = new File(dir(c), Integer.toHexString(name.toLowerCase(Locale.ROOT).hashCode()) + ".jpg");
        return f.isFile() ? f : null;
    }

    /** The saved photo, or a new download; null if neither source has one (tried again after a day). */
    static File photo(Context c, String artist) {
        String name = mainArtist(artist);
        if (name.isEmpty()) return null;
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) return null;
        String key = Integer.toHexString(name.toLowerCase(Locale.ROOT).hashCode());
        File f = new File(d, key + ".jpg"), none = new File(d, key + ".none");
        if (f.isFile()) return f;
        if (none.isFile() && System.currentTimeMillis() - none.lastModified() < 24 * 3600_000L) return null;
        try {
            String url = audioDb(name);
            if (url == null) url = deezer(name);
            if (url != null) {
                byte[] bytes = DailyPhoto.get(url);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
                prune(d);
                return f;
            }
            new FileOutputStream(none).close();
        } catch (Exception e) {
            AppLog.w("artist photo: " + e.getMessage());
        }
        return null;
    }

    private static String audioDb(String name) throws Exception {
        String json = new String(DailyPhoto.get("https://www.theaudiodb.com/api/v1/json/123/search.php?s="
                + URLEncoder.encode(name, "UTF-8")), StandardCharsets.UTF_8);
        JSONArray a = new JSONObject(json).optJSONArray("artists");
        if (a == null || a.length() == 0) return null;
        JSONObject o = a.getJSONObject(0);
        for (String k : new String[] {"strArtistFanart", "strArtistFanart2", "strArtistWideThumb"}) {
            String v = o.optString(k, "");
            if (v.startsWith("http") && !v.equals("null")) return v;
        }
        return null;
    }

    private static String deezer(String name) throws Exception {
        String json = new String(DailyPhoto.get("https://api.deezer.com/search/artist?q=" + URLEncoder.encode(name, "UTF-8")),
                StandardCharsets.UTF_8);
        JSONArray a = new JSONObject(json).optJSONArray("data");
        if (a == null) return null;
        // The best-known artist of that name (namesakes with a handful of fans come first sometimes).
        JSONObject best = null;
        for (int i = 0; i < a.length() && i < 5; i++) {
            JSONObject o = a.getJSONObject(i);
            if (!o.optString("name").equalsIgnoreCase(name)) continue;
            if (best == null || o.optInt("nb_fan") > best.optInt("nb_fan")) best = o;
        }
        if (best == null) return null;
        String v = best.optString("picture_xl", "");
        return v.startsWith("http") && !v.contains("/artist//") ? v : null;
    }

    /** At most 40 artists on the TV. */
    private static void prune(File d) {
        File[] all = d.listFiles();
        if (all == null || all.length <= 40) return;
        java.util.Arrays.sort(all, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
        for (int i = 0; i < all.length - 40; i++) all[i].delete();
    }
}
