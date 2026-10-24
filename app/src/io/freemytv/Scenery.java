package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver's other photos (Settings > Screensaver photos): landscapes or architecture in 4K, from Wikimedia
 * Commons' featured pictures (free to show with credit: each caption names the photographer and the license). The list
 * comes once a week, in a new order each day; each day's first 20 are downloaded (a few at a time) and cycled.
 * Same shape as Recs for the screensaver: showcase() and readyImage().
 */
final class Scenery {
    static final String KIND = "scenery";
    private static final String API = "https://commons.wikimedia.org/w/api.php";
    // Wikimedia asks every client to say who it is
    private static final String UA = "FreeMyTV/1.0 (https://freemytv.io)";
    private static final long LIST_MS = 7 * 24 * 3600_000L;
    private static final int AHEAD = 3, KEEP = 20, PER_CATEGORY = 300;     // 20 a day: a 10-minute loop at 30 s each

    private static Scenery instance;

    static synchronized Scenery get(Context c) {
        if (instance == null) instance = new Scenery(c.getApplicationContext());
        return instance;
    }

    static boolean isScenery(Recs.Pick p) {
        return p != null && KIND.equals(p.kind);
    }

    /** The caption's top line: NATURE or ARCHITECTURE. */
    static String label() {
        String l = Prefs.saverPhotos().equals("architecture") ? Ui.tr("Arquitetura", "Architecture") : Ui.tr("Natureza", "Nature");
        return l.toUpperCase(Ui.locale());
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Recs.Listener> listeners = new ArrayList<>();
    private volatile List<Recs.Pick> list = new ArrayList<>();
    private volatile String listFor = "";
    private volatile long listDay;
    private volatile boolean busy;

    private Scenery(Context c) {
        context = c;
    }

    void addListener(Recs.Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Recs.Listener l) { listeners.remove(l); }

    /** The photos ready to show (downloaded), in today's order; asks for more in the background. */
    List<Recs.Pick> showcase() {
        prefetch();
        List<Recs.Pick> out = new ArrayList<>();
        if (!listFor.equals(Prefs.saverPhotos())) return out;
        for (Recs.Pick p : list) if (file(p).exists()) out.add(p);
        return out;
    }

    File readyImage(Recs.Pick p) {
        File f = file(p);
        return f.exists() ? f : null;
    }

    /** Fetches the list if it's missing or old, and the next few photos not downloaded yet. */
    void prefetch() {
        final String kind = Prefs.saverPhotos();
        if (kind.equals("shows") || busy) return;
        busy = true;
        io.execute(() -> {
            try {
                long day = System.currentTimeMillis() / (24 * 3600_000L);
                if (!kind.equals(listFor) || list.isEmpty() || day != listDay) list = load(kind);
                listFor = kind;
                listDay = day;
                // Today's photos: the first KEEP in today's order, AHEAD more each time until they're all here
                int fetched = 0;
                java.util.Set<String> today = new java.util.HashSet<>();
                for (int i = 0; i < Math.min(KEEP, list.size()); i++) {
                    Recs.Pick p = list.get(i);
                    today.add(file(p).getName());
                    if (fetched >= AHEAD || file(p).exists()) continue;
                    try {
                        download(p.backdrop, file(p));
                        fetched++;
                        notifyListeners();
                    } catch (IOException e) {
                        AppLog.w("scenery photo: " + e.getMessage());
                    }
                }
                trim(kind, today);
            } catch (IOException | JSONException | RuntimeException e) {
                AppLog.w("scenery: " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    private void notifyListeners() {
        main.post(() -> { for (Recs.Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    private File dir(String kind) {
        File d = new File(context.getCacheDir(), "scenery/" + kind);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private File file(Recs.Pick p) {
        return new File(dir(listFor.isEmpty() ? Prefs.saverPhotos() : listFor), Integer.toHexString(p.id) + ".jpg");
    }

    /** Only today's photos stay (and nothing of the other kind): yesterday's go. */
    private void trim(String kind, java.util.Set<String> today) {
        File[] all = dir(kind).listFiles((d, n) -> n.endsWith(".jpg") || n.endsWith(".part"));
        if (all != null) for (File f : all) if (!today.contains(f.getName())) //noinspection ResultOfMethodCallIgnored
            f.delete();
        File[] other = dir(kind.equals("nature") ? "architecture" : "nature").listFiles();
        if (other != null) for (File f : other) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    // MARK: - Wikimedia Commons

    private static String[] categories(String kind) {
        return kind.equals("architecture")
                ? new String[] {"Featured pictures of buildings", "Featured pictures of architecture"}
                : new String[] {"Featured pictures of landscapes", "Featured pictures of mountains"};
    }

    /** The list (saved for a week), in today's order. */
    private List<Recs.Pick> load(String kind) throws IOException, JSONException {
        File saved = new File(context.getFilesDir(), "scenery-" + kind + ".json");
        JSONArray a;
        if (saved.exists() && System.currentTimeMillis() - saved.lastModified() < LIST_MS) {
            a = new JSONArray(new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8));
        } else {
            a = new JSONArray();
            for (String category : categories(kind)) fetchCategory(category, a);
            if (a.length() == 0) throw new IOException("Wikimedia Commons sent no photos");
            File tmp = new File(saved.getPath() + ".tmp");
            Files.write(tmp.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(saved)) throw new IOException("list not saved");
        }
        List<Recs.Pick> out = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Recs.Pick p = new Recs.Pick();
            p.kind = KIND;
            p.id = o.getString("url").hashCode();
            if (!seen.add(p.id)) continue;     // in both categories
            p.title = o.getString("title");
            p.backdrop = o.getString("url");
            String by = o.optString("artist"), license = o.optString("license");
            p.meta = Ui.tr("Foto: ", "Photo: ", "Foto: ") + (by.isEmpty() ? "" : by + " · ")
                    + (license.isEmpty() ? "" : license + " · ") + "Wikimedia Commons";
            out.add(p);
        }
        Collections.shuffle(out, new Random(System.currentTimeMillis() / (24 * 3600_000L)));     // a new order each day
        return out;
    }

    /** One category's featured pictures: landscape (not panoramas), at least 4K wide, as 3840 px images. */
    private static void fetchCategory(String category, JSONArray out) throws IOException, JSONException {
        String next = null;
        int taken = 0;
        do {
            String url = API + "?action=query&format=json&generator=categorymembers&gcmtype=file&gcmlimit=100"
                    + "&gcmtitle=" + URLEncoder.encode("Category:" + category, "UTF-8")
                    + "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=3840"
                    + "&iiextmetadatafilter=Artist%7CLicenseShortName%7CObjectName"
                    + (next == null ? "" : "&gcmcontinue=" + URLEncoder.encode(next, "UTF-8") + "&continue=gcmcontinue%7C%7C");
            JSONObject r = new JSONObject(get(url));
            JSONObject pages = r.optJSONObject("query") == null ? null : r.getJSONObject("query").optJSONObject("pages");
            if (pages != null) {
                for (java.util.Iterator<String> it = pages.keys(); it.hasNext(); ) {
                    JSONObject page = pages.getJSONObject(it.next());
                    JSONArray info = page.optJSONArray("imageinfo");
                    if (info == null || info.length() == 0) continue;
                    JSONObject ii = info.getJSONObject(0);
                    int w = ii.optInt("width"), h = ii.optInt("height");
                    String thumb = ii.optString("thumburl");
                    if (w < 3840 || h == 0 || w / (float) h < 1.3f || w / (float) h > 2.0f || thumb.isEmpty()) continue;
                    JSONObject meta = ii.optJSONObject("extmetadata");
                    out.put(new JSONObject().put("url", thumb)
                            .put("title", name(meta, page.optString("title")))
                            .put("artist", plain(value(meta, "Artist")))
                            .put("license", plain(value(meta, "LicenseShortName"))));
                    taken++;
                }
            }
            JSONObject cont = r.optJSONObject("continue");
            next = cont == null ? null : cont.optString("gcmcontinue", null);
        } while (next != null && taken < PER_CATEGORY);
    }

    private static String value(JSONObject meta, String key) {
        JSONObject o = meta == null ? null : meta.optJSONObject(key);
        return o == null ? "" : o.optString("value");
    }

    /** The photo's name: its own title when it has one, else the file's name without "File:" and the extension. */
    private static String name(JSONObject meta, String file) {
        String own = plain(value(meta, "ObjectName"));
        if (!own.isEmpty() && own.length() < 80) return own;
        String n = file.startsWith("File:") ? file.substring(5) : file;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.replace('_', ' ').trim();
    }

    /** Commons' fields are HTML (a link to the author): the text only, in one line. */
    private static String plain(String html) {
        String s = html.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
                .replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        return s.length() > 60 ? s.substring(0, 57).trim() + "…" : s;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(30_000);
        h.setRequestProperty("User-Agent", UA);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            h.disconnect();
        }
    }

    private static void download(String url, File to) throws IOException {
        File part = new File(to.getPath() + ".part");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(60_000);
        h.setRequestProperty("User-Agent", UA);
        try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            h.disconnect();
        }
        if (!part.renameTo(to)) throw new IOException("photo not saved");
    }
}
