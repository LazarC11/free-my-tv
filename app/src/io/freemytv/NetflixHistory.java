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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What you watched on Netflix, for the Explore tab. Netflix on TV tells only the system home screen what plays (Watch
 * Next, which no installed app can read), so this reads the Viewing activity of your profiles from netflix.com instead,
 * as the account page does, with the two login cookies in config.properties (netflix_id, secure_netflix_id; off without
 * them; netflix_profiles picks profiles by name, empty = all). It is Netflix's website API, not a public one: it may
 * change. The covers come from TMDB. The cookies never go to the log.
 */
final class NetflixHistory {
    private NetflixHistory() {}

    static final String PKG = "com.netflix.ninja";
    private static final String FILE = "netflix.json";
    private static final long REFRESH_MS = 10 * 60_000;
    private static final int COUNT = 3;     // the Explore tab's three cards
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/126.0.0.0 Safari/537.36";
    private static final String AUI = "https://www.netflix.com/api/aui/pathEvaluator/web/%5E2.0.0";
    private static final String ROUTING = "{\"path\":\"/nq/aui/endpoint/%5E1.0.0-web/pathEvaluator\",\"control_tag\":\"auinqweb\"}";
    private static final Pattern AUTH = Pattern.compile("\"authURL\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern GUID = Pattern.compile("\"guid\"\\s*:\\s*\"([A-Z0-9]{20,30})\"");
    private static final Pattern HEX = Pattern.compile("\\\\x([0-9A-Fa-f]{2})");
    private static final ExecutorService FETCH = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    static boolean on() {
        return !Config.NETFLIX_ID.trim().isEmpty() && !Config.SECURE_NETFLIX_ID.trim().isEmpty();
    }

    /** The saved list, newest first, each with its cover. Not on the UI thread. */
    static List<NowPlaying.Item> recent(Context c) {
        List<NowPlaying.Item> out = new ArrayList<>();
        if (!on()) return out;
        File dir = c.getFilesDir();
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(new File(dir, FILE).toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                NowPlaying.Item it = new NowPlaying.Item();
                it.pkg = PKG;
                it.title = o.optString("title");
                it.subtitle = o.optString("subtitle");
                it.at = o.optLong("at");
                it.netflixId = o.optLong("id");
                it.image = BitmapFactory.decodeFile(new File(dir, o.optString("image")).getPath());
                if (it.image != null) out.add(it);
            }
        } catch (IOException | JSONException e) {
            // none yet
        }
        return out;
    }

    /** Fetches again if the saved list is older than REFRESH_MS; `done` runs (on that thread) when it changed. */
    static void refreshIfStale(Context c, Runnable done) {
        if (!on()) return;
        final Context app = c.getApplicationContext();
        final File saved = new File(app.getFilesDir(), FILE);
        if (System.currentTimeMillis() - saved.lastModified() < REFRESH_MS) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        FETCH.execute(() -> {
            try {
                refresh(app);
                done.run();
            } catch (IOException | JSONException | RuntimeException e) {
                AppLog.w("netflix: " + e.getMessage());
                // Try again later, not on every visit (the old list stays)
                try {
                    if (!saved.exists()) Files.write(saved.toPath(), "[]".getBytes(StandardCharsets.UTF_8));
                    saved.setLastModified(System.currentTimeMillis());
                } catch (IOException ignored) {}
            } finally {
                RUNNING.set(false);
            }
        });
    }

    /** One title in a profile's Viewing activity. */
    private static final class Viewed {
        String show, title, subtitle, profile;
        long at, id;
    }

    private static void refresh(Context c) throws IOException, JSONException {
        // The account's Viewing activity page: its token for the API and the IDs it mentions (the profiles among them).
        String page = text(open("https://www.netflix.com/viewingactivity"), true);
        Matcher auth = AUTH.matcher(page);
        if (!auth.find()) throw new IOException("Netflix didn't open the account page (copy the cookies again)");
        String token = unescape(auth.group(1));
        Set<String> ids = new LinkedHashSet<>();
        for (Matcher m = GUID.matcher(page); m.find(); ) ids.add(m.group(1));
        Set<String> wanted = new HashSet<>();
        for (String name : Config.NETFLIX_PROFILES.split(",")) if (!name.trim().isEmpty()) wanted.add(name.trim().toLowerCase(Locale.ROOT));

        List<Viewed> all = new ArrayList<>();
        Set<String> profiles = new HashSet<>();
        for (String id : ids) {
            JSONObject activity = activity(id, token);
            if (activity == null) continue;     // not a profile (or one with a PIN)
            JSONObject info = activity.optJSONObject("profileInfo");
            String name = info == null ? "" : info.optString("profileName");
            if (info == null || !id.equals(info.optString("guid")) || !profiles.add(id)) continue;
            if (!wanted.isEmpty() && !wanted.contains(name.toLowerCase(Locale.ROOT))) continue;
            JSONArray items = activity.optJSONArray("viewedItems");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject v = items.getJSONObject(i);
                Viewed w = new Viewed();
                long series = v.optLong("series");
                String seriesTitle = v.optString("seriesTitle");
                boolean episode = series != 0 && !seriesTitle.isEmpty();
                w.show = episode ? "s" + series : "m" + v.optLong("movieID");
                w.title = episode ? seriesTitle : v.optString("title");
                if (episode) {
                    String season = v.optString("seasonDescriptor"), ep = v.optString("episodeTitle");
                    w.subtitle = season.isEmpty() ? ep : ep.isEmpty() ? season : season + ": " + ep;
                } else {
                    w.subtitle = "";
                }
                w.at = v.optLong("date");
                w.id = v.optLong("movieID");
                w.profile = name;
                all.add(w);
            }
        }
        if (profiles.isEmpty()) throw new IOException("no profiles answered (copy the cookies again)");
        all.sort((a, b) -> Long.compare(b.at, a.at));

        // Newest first, one card per show (its newest episode, from whichever profile watched it last).
        boolean several = wanted.size() != 1 && profiles.size() > 1;
        File dir = c.getFilesDir();
        JSONArray out = new JSONArray();
        Set<String> shows = new HashSet<>(), keep = new HashSet<>();
        Recs recs = Recs.get(c);
        for (int i = 0; i < all.size() && out.length() < COUNT; i++) {
            Viewed w = all.get(i);
            if (!shows.add(w.show)) continue;
            String image = "netflix-hd-" + w.show + ".jpg";     // "hd": 1280 px (the old 780 px ones are cleared)
            File f = new File(dir, image);
            if (!f.exists() && !cover(recs, w.title, w.show.startsWith("s"), f)) {
                AppLog.w("netflix: no cover for “" + w.title + "”");
                continue;
            }
            keep.add(image);
            String subtitle = several ? (w.subtitle.isEmpty() ? w.profile : w.profile + " · " + w.subtitle) : w.subtitle;
            out.put(new JSONObject().put("title", w.title).put("subtitle", subtitle).put("at", w.at).put("id", w.id)
                    .put("image", image));
        }
        File tmp = new File(dir, FILE + ".tmp");
        Files.write(tmp.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(new File(dir, FILE))) throw new IOException("list not saved");
        File[] old = dir.listFiles((d, n) -> n.startsWith("netflix-") && n.endsWith(".jpg"));
        if (old != null) for (File o : old) if (!keep.contains(o.getName())) o.delete();
        AppLog.w("netflix: " + profiles.size() + " profiles, " + out.length() + " recently watched");
    }

    /** One profile's Viewing activity (page 0), as the account page asks for it; null if Netflix refuses that ID. */
    private static JSONObject activity(String guid, String token) throws IOException, JSONException {
        String query = "method=call&falcor_server=0.1.0&callPath="
                + URLEncoder.encode("[\"aui\",\"viewingActivity\",0,20]", "UTF-8");
        String body = "param=" + URLEncoder.encode(new JSONObject().put("guid", guid).toString(), "UTF-8")
                + "&authURL=" + URLEncoder.encode(token, "UTF-8");
        HttpURLConnection h = open(AUI + "?" + query);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        h.setRequestProperty("x-netflix.request.routing", ROUTING);
        try (OutputStream o = h.getOutputStream()) {
            o.write(body.getBytes(StandardCharsets.UTF_8));
        }
        if (h.getResponseCode() != 200) {
            h.disconnect();
            return null;
        }
        JSONObject graph = new JSONObject(text(h, false)).optJSONObject("jsonGraph");
        JSONObject aui = graph == null ? null : graph.optJSONObject("aui");
        JSONObject va = aui == null ? null : aui.optJSONObject("viewingActivity");
        if (va == null) return null;
        JSONObject value = va.optJSONObject("value");
        return value != null ? value : va;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(20_000);
        h.setInstanceFollowRedirects(false);    // a redirect means the login page: the cookies expired
        h.setRequestProperty("User-Agent", UA);
        h.setRequestProperty("Cookie", "NetflixId=" + Config.NETFLIX_ID.trim() + "; SecureNetflixId=" + Config.SECURE_NETFLIX_ID.trim());
        return h;
    }

    /**
     * The response as text. `partial`: Netflix often closes the account page before its end; what came is kept (the
     * token and the IDs are near the top).
     */
    private static String text(HttpURLConnection h, boolean partial) throws IOException {
        try {
            int code = h.getResponseCode();
            if (code != 200) throw new IOException("netflix.com returned HTTP " + code + (code / 100 == 3 ? " (copy the cookies again)" : ""));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } catch (IOException e) {
                if (!partial || out.size() == 0) throw e;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            h.disconnect();
        }
    }

    /** The page's JavaScript string escapes (\x2F) as characters. */
    private static String unescape(String s) {
        Matcher m = HEX.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        m.appendTail(out);
        return out.toString();
    }

    /** A wide TMDB backdrop for the title (Recs.backdropFor), saved to `out`; false if TMDB has none. */
    private static boolean cover(Recs recs, String title, boolean show, File out) throws IOException {
        Bitmap b = recs.backdropFor(title, show ? "tv" : "movie");
        if (b == null) return false;
        File tmp = new File(out.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, o);
        }
        return tmp.renameTo(out);
    }
}
