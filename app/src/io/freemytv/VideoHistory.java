package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What you watched in video apps (YouTube, Stremio, Netflix…), seen in their media sessions by MediaListener, which
 * runs whenever the TV does. The home screen shows the newest as its background for a few minutes after you come
 * back, then in Continue watching. Music apps are left to the home screen's music card. Kept in a small file (the home
 * screen runs in another process); the pictures beside it: YouTube's own thumbnail, else TMDB's backdrop by the title.
 */
final class VideoHistory {
    private VideoHistory() {}

    private static final String FILE = "videos.json";
    private static final int COUNT = 12;
    private static final long SAVE_EVERY = 30_000;

    /** Music players: their sessions are the home screen's music card, never a video. */
    private static final Set<String> MUSIC = new HashSet<>(Arrays.asList(
            "com.spotify.tv.android", "com.spotify.music", "com.google.android.youtube.tvmusic",
            "com.google.android.apps.youtube.music", "deezer.android.tv", "deezer.android.app", "com.aspiro.tidal",
            "com.soundcloud.android", "com.amazon.mp3", "com.apple.android.music", "com.pandora.android"));
    /** Video apps: always a video, whatever picture they send. */
    private static final Set<String> VIDEO = new HashSet<>(Arrays.asList(
            "com.google.android.youtube.tv", "com.stremio.one", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.wbd.stream", "com.hbo.hbonow", "com.disney.disneyplus", "com.apple.atve.androidtv.appletv",
            "com.hulu.livingroomplus", "com.cbs.ott", "com.peacocktv.peacockandroid", "com.globo.globotv",
            "com.iqiyi.i18n.tv", "com.mercadolibre.android.mplay_tv"));

    private static final Pattern YT_THUMB = Pattern.compile("i\\.ytimg\\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/");
    private static final Pattern YT_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final ExecutorService SAVER = Executors.newSingleThreadExecutor();

    /** A video, not music: a video app, or (another app) a wide picture. */
    static boolean isVideo(String pkg, Bitmap art) {
        if (pkg == null || MUSIC.contains(pkg)) return false;
        if (VIDEO.contains(pkg)) return true;
        return art != null && art.getWidth() >= art.getHeight() * 1.3f;
    }

    private static String lastKey = "";
    private static long lastSaved;
    private static boolean lastPlaying;

    /** A session changed (MediaListener, main thread): a video playing or paused is saved, at most every 30 s. */
    static void seen(Context c, MediaController m) {
        MediaMetadata md = m.getMetadata();
        PlaybackState s = m.getPlaybackState();
        if (md == null || s == null) return;
        String pkg = m.getPackageName();
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (!isVideo(pkg, art)) return;
        String title = text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (title.isEmpty()) return;
        int state = s.getState();
        boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        if (!playing && state != PlaybackState.STATE_PAUSED) return;
        String key = pkg + "|" + title;
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && playing == lastPlaying && now - lastSaved < SAVE_EVERY) return;
        lastKey = key;
        lastPlaying = playing;
        lastSaved = now;

        final JSONObject o = new JSONObject();
        try {
            o.put("pkg", pkg).put("title", title)
                    .put("subtitle", text(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST))
                    .put("at", now).put("position", Math.max(0, s.getPosition()))
                    .put("duration", Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION)));
        } catch (JSONException e) {
            return;
        }
        String artUri = text(md, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        String video = youTubeId(text(md, MediaMetadata.METADATA_KEY_MEDIA_ID), artUri);
        final Bitmap own = art == null ? null : art.copy(Bitmap.Config.ARGB_8888, false);   // the app may recycle its own
        final Context app = c.getApplicationContext();
        SAVER.execute(() -> save(app, o, video, artUri, own));
    }

    /** The YouTube video's ID, from the session's media ID or its thumbnail's address; null if none. */
    private static String youTubeId(String mediaId, String artUri) {
        if (mediaId != null && YT_ID.matcher(mediaId).matches()) return mediaId;
        if (artUri != null) {
            Matcher m = YT_THUMB.matcher(artUri);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static synchronized void save(Context c, JSONObject o, String video, String artUri, Bitmap own) {
        File dir = c.getFilesDir();
        try {
            String pkg = o.getString("pkg"), title = o.getString("title");
            // Named by the video's ID too: a picture saved under the title with the wrong video is never reused.
            String image = "video-hd-" + Integer.toHexString((pkg + "|" + title + "|" + video).hashCode()) + ".jpg";
            File f = new File(dir, image);
            if (!f.exists()) picture(c, pkg, title, video, artUri, own, f);
            if (f.exists()) o.put("image", image);
            if (video != null) o.put("video", video);
            JSONArray old = read(dir), out = new JSONArray().put(o);
            Set<String> keep = new HashSet<>();
            keep.add(image);
            for (int i = 0; i < old.length() && out.length() < COUNT; i++) {
                JSONObject e = old.getJSONObject(i);
                if (e.optString("pkg").equals(pkg) && e.optString("title").equals(title)) continue;
                out.put(e);
                keep.add(e.optString("image"));
            }
            File tmp = new File(dir, FILE + ".tmp");
            Files.write(tmp.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(new File(dir, FILE))) throw new IOException("list not saved");
            File[] all = dir.listFiles((d, n) -> n.startsWith("video-") && n.endsWith(".jpg"));
            if (all != null) for (File a : all) if (!keep.contains(a.getName())) a.delete();
        } catch (IOException | JSONException | RuntimeException e) {
            AppLog.w("video history: " + e.getMessage());
        }
    }

    /**
     * The video's picture, saved to `out`: YouTube's own (the sharpest it has), else TMDB's wide backdrop found by the
     * title (movies and shows: Stremio, Netflix…), else the picture the app sent.
     */
    private static void picture(Context c, String pkg, String title, String video, String artUri, Bitmap own, File out) {
        Bitmap b = null;
        if (video != null) {
            // The full-HD thumbnail, kept as YouTube made it (saving it again would soften it).
            try {
                byte[] bytes = DailyPhoto.get("https://i.ytimg.com/vi/" + video + "/maxresdefault.jpg");
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth >= 1280) {
                    File tmp = new File(out.getPath() + ".tmp");
                    java.nio.file.Files.write(tmp.toPath(), bytes);
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(out);
                    return;
                }
            } catch (IOException | RuntimeException e) {
                // no full-HD one for this video
            }
            // Smaller ones are 4:3 with black bars above and below: only the 16:9 picture between them.
            for (String size : new String[] {"sddefault", "hqdefault"}) {
                b = download("https://i.ytimg.com/vi/" + video + "/" + size + ".jpg");
                if (b != null && b.getWidth() > 120) {     // YouTube answers a missing size with a 120 px gray picture
                    int h = b.getWidth() * 9 / 16;
                    if (h < b.getHeight()) b = Bitmap.createBitmap(b, 0, (b.getHeight() - h) / 2, b.getWidth(), h);
                    break;
                }
                b = null;
            }
        }
        if (b == null && !pkg.contains("youtube")) {
            try {
                b = Recs.get(c).backdropFor(title, null);
            } catch (IOException | RuntimeException e) {
                AppLog.w("video history: no TMDB picture: " + e.getMessage());
            }
        }
        if (b == null && artUri != null && artUri.startsWith("http")) b = download(artUri);
        if (b == null) b = own;
        if (b == null) return;
        File tmp = new File(out.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.JPEG, 96, os);
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(out);
    }

    private static Bitmap download(String url) {
        try {
            byte[] bytes = DailyPhoto.get(url);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The saved videos as Continue watching entries, newest first. Not on the UI thread. */
    static List<WatchNext.Entry> recent(Context c) {
        List<WatchNext.Entry> out = new ArrayList<>();
        File dir = c.getFilesDir();
        JSONArray a = read(dir);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("image").isEmpty()) continue;
            File image = new File(dir, o.optString("image"));
            if (!image.exists()) continue;
            WatchNext.Entry e = new WatchNext.Entry();
            e.pkg = o.optString("pkg");
            e.title = o.optString("title");
            e.subtitle = o.optString("subtitle");
            e.engaged = o.optLong("at");
            e.image = Uri.fromFile(image).toString();
            long position = o.optLong("position"), duration = o.optLong("duration");
            if (position > 0 && duration > 0) {
                e.progress = Math.min(1f, position / (float) duration);
                e.left = Math.max(0, duration - position);
            }
            String video = o.optString("video");
            if (!video.isEmpty()) {
                e.intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + video)).setPackage(e.pkg);
            }
            out.add(e);
        }
        return out;
    }

    private static JSONArray read(File dir) {
        try {
            return new JSONArray(new String(Files.readAllBytes(new File(dir, FILE).toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence s = m.getText(k);
            if (s != null && s.toString().trim().length() > 0) return s.toString().trim();
        }
        return "";
    }
}
