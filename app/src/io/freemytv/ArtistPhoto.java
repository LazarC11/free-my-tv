package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A photo of the artist playing, for the music screensaver's background (Spotify keeps its own backdrops inside the
 * app). TheAudioDB's wide fan art first (made for backgrounds), else Deezer's large artist photo (square, it covers more
 * artists); both free, without a key (TheAudioDB's public one). Kept on disk per artist. Not on the UI thread.
 */
final class ArtistPhoto {
    private ArtistPhoto() {}

    private static final String UA = "FreeMyTV/1.0 (https://freemytv.io)";

    /** The photo for the artist (the first one named: "Seka Aleksić x Rasta" → Seka Aleksić), or null if none. */
    static Bitmap get(Context c, String artist) {
        String name = main(artist);
        if (name.isEmpty()) return null;
        File dir = new File(c.getCacheDir(), "artists");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File saved = new File(dir, Integer.toHexString(name.toLowerCase(Locale.ROOT).hashCode()) + ".jpg");
        File none = new File(saved.getPath() + ".none");
        if (saved.exists()) return BitmapFactory.decodeFile(saved.getPath());
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 7 * 24 * 3600_000L) return null;
        String url = null;
        try {
            url = fanArt(name);
            if (url == null) url = deezer(name);
        } catch (IOException | JSONException | RuntimeException e) {
            AppLog.w("artist photo: " + e.getMessage());
            return null;    // no internet now: try again next time
        }
        try {
            if (url == null) {
                //noinspection ResultOfMethodCallIgnored
                none.createNewFile();
                return null;
            }
            byte[] bytes = read(url);
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (b == null) return null;
            try (FileOutputStream out = new FileOutputStream(saved)) {
                out.write(bytes);
            }
            return b;
        } catch (IOException e) {
            AppLog.w("artist photo: " + e.getMessage());
            return null;
        }
    }

    /** The first artist of "A, B", "A x B", "A & B", "A feat. B". */
    static String main(String artist) {
        if (artist == null) return "";
        String a = artist;
        for (String sep : new String[] {",", " x ", " X ", " & ", " feat", " ft.", " Feat"}) {
            int at = a.indexOf(sep);
            if (at > 0) a = a.substring(0, at);
        }
        return a.trim();
    }

    private static String fanArt(String name) throws IOException, JSONException {
        JSONObject r = new JSONObject(new String(read("https://www.theaudiodb.com/api/v1/json/123/search.php?s="
                + URLEncoder.encode(name, "UTF-8")), StandardCharsets.UTF_8));
        JSONArray artists = r.optJSONArray("artists");
        JSONObject a = artists == null || artists.length() == 0 ? null : artists.getJSONObject(0);
        if (a == null) return null;
        for (String key : new String[] {"strArtistFanart", "strArtistFanart2", "strArtistWideThumb"}) {
            String v = a.optString(key, "");
            if (v.startsWith("http")) return v;
        }
        return null;
    }

    private static String deezer(String name) throws IOException, JSONException {
        JSONObject r = new JSONObject(new String(read("https://api.deezer.com/search/artist?limit=1&q="
                + URLEncoder.encode(name, "UTF-8")), StandardCharsets.UTF_8));
        JSONArray data = r.optJSONArray("data");
        JSONObject a = data == null || data.length() == 0 ? null : data.getJSONObject(0);
        if (a == null) return null;
        String pic = a.optString("picture_xl", "");
        // Artists without a photo get Deezer's placeholder (".../artist//...")
        return pic.startsWith("http") && !pic.contains("/artist//") ? pic : null;
    }

    private static byte[] read(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(20_000);
        h.setRequestProperty("User-Agent", UA);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            h.disconnect();
        }
    }
}
