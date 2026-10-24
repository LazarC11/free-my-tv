package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What you watched last, for the home screen's Explore tab (the three most recent videos; music shows live instead,
 * MediaNow): the title, the channel or show and the picture each
 * app reports to the TV's media controls while it plays (YouTube does; Netflix reports nothing). Android only lets a
 * notification listener read those, so this is one, turned on through ADB in Free My TV mode; it never reads the
 * notifications themselves. Saved to files (the home screen is another process).
 */
public final class NowPlaying extends NotificationListenerService {
    private static final String FILE = "played.json";
    private static final int HISTORY = 3;    // the Explore tab's three cards
    private static final long MAX_AGE_MS = 14 * 24 * 3600_000L;
    private static final Pattern VIDEO_ID = Pattern.compile("(?:/vi(?:_webp)?/|[?&]v=|youtu\\.be/)([\\w-]{11})");
    private static final ExecutorService SAVER = Executors.newSingleThreadExecutor();

    /** One thing played: what its card shows. */
    static final class Item {
        String pkg, title, subtitle, videoId;
        long at, netflixId;     // netflixId: from NetflixHistory (0 = none)
        Bitmap image;
    }

    private MediaSessionManager sessions;
    private final List<MediaController> controllers = new ArrayList<>();
    private final List<MediaController.Callback> callbacks = new ArrayList<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::watch;
    private String lastKey = "";
    private static final long SETTLE_MS = 3000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaController pending;
    private final Runnable settled = () -> { if (pending != null) check(pending); };

    @Override public void onListenerConnected() {
        sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName me = new ComponentName(this, NowPlaying.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me);
            watch(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            AppLog.w("now playing: " + e.getMessage());
        }
    }

    @Override public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        main.removeCallbacks(settled);
        pending = null;
        watch(null);
    }

    private void watch(List<MediaController> list) {
        for (int i = 0; i < controllers.size(); i++) controllers.get(i).unregisterCallback(callbacks.get(i));
        controllers.clear();
        callbacks.clear();
        if (list == null) return;
        for (final MediaController mc : list) {
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState state) { later(mc); }
                @Override public void onMetadataChanged(MediaMetadata metadata) { later(mc); }
            };
            mc.registerCallback(cb);
            controllers.add(mc);
            callbacks.add(cb);
            later(mc);
        }
    }

    /**
     * Read once the app has settled: YouTube sends the next video's title first and its picture a moment later, so
     * reading right away paired the new title with the previous picture.
     */
    private void later(MediaController mc) {
        pending = mc;
        main.removeCallbacks(settled);
        main.postDelayed(settled, SETTLE_MS);
    }

    /** Playing with a title and a picture: saved (again when the picture changes). */
    private void check(MediaController mc) {
        PlaybackState state = mc.getPlaybackState();
        MediaMetadata m = mc.getMetadata();
        if (state == null || state.getState() != PlaybackState.STATE_PLAYING || m == null) return;
        String title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) return;
        // The art first: Spotify's display icon is its own logo
        Bitmap art = image(m, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String key = mc.getPackageName() + "\n" + title + "\n" + (art == null ? 0 : art.getGenerationId() + "@" + art.getWidth());
        if (key.equals(lastKey)) return;
        lastKey = key;
        final Item it = new Item();
        it.pkg = mc.getPackageName();
        it.title = title;
        it.subtitle = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
        it.at = System.currentTimeMillis();
        it.image = art;
        final String uri = text(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        it.videoId = videoId(m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), m.getString(MediaMetadata.METADATA_KEY_MEDIA_URI), uri);
        final String big = artUrl(uri);
        AppLog.w("now playing in " + it.pkg + ": " + new TreeSet<>(m.keySet()) + (it.videoId != null ? ", video " + it.videoId : "")
                + (big != null ? ", art " + big : ""));
        if (it.image != null) it.image = it.image.copy(Bitmap.Config.ARGB_8888, false);    // the app may recycle its own
        SAVER.execute(() -> {
            // A small picture (Spotify's is 160 px) looks blurry on a card: the bigger one, when the app says where
            if ((it.image == null || Math.max(it.image.getWidth(), it.image.getHeight()) < 400) && big != null) {
                Bitmap b = download(big);
                if (b != null) it.image = b;
            }
            // Movies and shows (Stremio and the like) send a small still of their own: TMDB's sharp backdrop, found
            // by the title, as for Netflix. Not for YouTube (its thumbnail is that video) nor for music.
            if (it.image != null && !music(it.image) && !it.pkg.contains("youtube")) {
                try {
                    Bitmap b = Recs.get(this).backdropFor(it.title, null);
                    if (b != null) it.image = b;
                } catch (IOException | RuntimeException e) {
                    AppLog.w("now playing: no TMDB picture: " + e.getMessage());
                }
            }
            save(this, it);
        });
    }

    /** Music (a square cover): Explore shows it live while it plays (MediaNow), not in the history of what you watched. */
    static boolean music(Bitmap image) {
        return image != null && image.getWidth() < image.getHeight() * 1.3f;
    }

    /** Where to download the picture: web addresses as they are, Spotify's own (spotify:image:…) at 640 px; or null. */
    static String artUrl(String uri) {
        if (uri == null) return null;
        if (uri.startsWith("https://") || uri.startsWith("http://")) return uri;
        if (uri.startsWith("spotify:image:")) {
            String id = uri.substring("spotify:image:".length());
            // Album covers: ab67616d + size (00004851 = 64, 00001e02 = 300, 0000b273 = 640) + the image's own ID
            if (id.startsWith("ab67616d") && id.length() > 16) id = "ab67616d0000b273" + id.substring(16);
            return "https://i.scdn.co/image/" + id;
        }
        return null;
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence s = m.getText(k);
            if (s != null && s.toString().trim().length() > 0) return s.toString().trim();
        }
        return null;
    }

    private static Bitmap image(MediaMetadata m, String... keys) {
        for (String k : keys) {
            Bitmap b = m.getBitmap(k);
            if (b != null) return b;
        }
        return null;
    }

    private static String videoId(String... candidates) {
        for (String s : candidates) {
            if (s == null) continue;
            Matcher v = VIDEO_ID.matcher(s);
            if (v.find()) return v.group(1);
            if (s.matches("[\\w-]{11}")) return s;
        }
        return null;
    }

    static Bitmap download(String uri) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                c.disconnect();
            }
        } catch (IOException | RuntimeException e) {
            AppLog.w("now playing picture: " + e.getMessage());
            return null;
        }
    }

    /** Only with a picture: a title alone would make a bare card. */
    private static void save(Context c, Item it) {
        if (it.image == null) {
            AppLog.w("now playing: no picture, not saved");
            return;
        }
        if (music(it.image)) return;    // songs show live, not in the history
        AppLog.w("now playing: saved, picture " + it.image.getWidth() + "×" + it.image.getHeight());
        File dir = c.getFilesDir();
        try {
            // Newest first; the same title played again moves to the front (with its newer picture).
            JSONArray old = list(dir), items = new JSONArray();
            String name = "played-" + Integer.toHexString((it.pkg + "\n" + it.title).hashCode()) + ".jpg";
            File tmp = new File(dir, name + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                it.image.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            if (!tmp.renameTo(new File(dir, name))) throw new IOException("picture not saved");
            items.put(new JSONObject().put("pkg", it.pkg).put("title", it.title).put("at", it.at).put("image", name)
                    .put("subtitle", it.subtitle == null ? "" : it.subtitle).put("videoId", it.videoId == null ? "" : it.videoId));
            Set<String> keep = new HashSet<>();
            keep.add(name);
            for (int i = 0; i < old.length() && items.length() < HISTORY; i++) {
                JSONObject o = old.getJSONObject(i);
                if (name.equals(o.optString("image"))) continue;
                items.put(o);
                keep.add(o.optString("image"));
            }
            File json = new File(dir, FILE + ".tmp");
            Files.write(json.toPath(), items.toString().getBytes(StandardCharsets.UTF_8));
            if (!json.renameTo(new File(dir, FILE))) throw new IOException("list not saved");
            new File(dir, "now-playing.json").delete();     // the first version's single item
            new File(dir, "now-playing.jpg").delete();
            File[] files = dir.listFiles((d, n) -> n.startsWith("played-") && n.endsWith(".jpg"));
            if (files != null) for (File f : files) if (!keep.contains(f.getName())) f.delete();
        } catch (IOException | JSONException e) {
            AppLog.w("now playing: " + e.getMessage());
        }
    }

    private static JSONArray list(File dir) {
        try {
            return new JSONArray(new String(Files.readAllBytes(new File(dir, FILE).toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONArray();    // none yet (or the single item of the first version)
        }
    }

    /** What played in the past 2 weeks, newest first, each with its picture. Not on the UI thread. */
    static List<Item> history(Context c) {
        File dir = c.getFilesDir();
        JSONArray a = list(dir);
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.at = o.optLong("at");
            if (System.currentTimeMillis() - it.at > MAX_AGE_MS) continue;
            it.pkg = o.optString("pkg");
            it.title = o.optString("title");
            it.subtitle = o.optString("subtitle");
            it.videoId = o.optString("videoId").isEmpty() ? null : o.optString("videoId");
            it.image = BitmapFactory.decodeFile(new File(dir, o.optString("image")).getPath());
            if (it.image != null && !music(it.image)) out.add(it);     // songs saved by an earlier version: left out
        }
        return out;
    }
}
