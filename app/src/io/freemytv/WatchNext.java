package io.freemytv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Continue watching": the titles the TV's apps put in Android TV's Watch Next list (TvContract.WatchNextPrograms), the
 * most recent first. Reading other apps' entries needs READ_EPG_DATA, which the switch to Free My TV mode grants over
 * ADB; without it the list is empty and the row doesn't show. Background thread only.
 */
final class WatchNext {
    private WatchNext() {}

    static final String PERMISSION = "com.android.providers.tv.permission.READ_EPG_DATA";
    private static final int MAX = 16;

    static final class Entry {
        String title = "", subtitle = "", pkg, image;
        Intent intent;
        float progress = -1;    // 0 to 1, or -1
        Bitmap card;            // the image at the card's size (loaded later)
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        if (!allowed(c)) return out;
        String[] columns = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
        };
        PackageManager pm = c.getPackageManager();
        Set<String> seen = new HashSet<>();
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, columns, null, null,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < MAX) {
                if (!cur.isNull(11) && cur.getInt(11) == 0) continue;   // hidden by its app
                Entry e = new Entry();
                e.pkg = cur.getString(0);
                e.title = text(cur, 1);
                if (e.title.isEmpty() || e.pkg == null || HomeApps.launchIntent(pm, e.pkg) == null) continue;
                String episode = text(cur, 2), season = text(cur, 3), number = text(cur, 4);
                String code = !season.isEmpty() && !number.isEmpty() ? "S" + season + " E" + number
                        : !number.isEmpty() ? "E" + number : "";
                e.subtitle = code.isEmpty() ? episode : episode.isEmpty() ? code : code + " · " + episode;
                if (e.subtitle.isEmpty()) e.subtitle = label(pm, e.pkg);
                e.image = !text(cur, 5).isEmpty() ? text(cur, 5) : text(cur, 6);
                String uri = text(cur, 7);
                if (!uri.isEmpty()) {
                    try {
                        e.intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
                    } catch (URISyntaxException ignored) {
                    }
                }
                long position = cur.isNull(8) ? 0 : cur.getLong(8), duration = cur.isNull(9) ? 0 : cur.getLong(9);
                if (position > 0 && duration > 0) e.progress = Math.min(1f, position / (float) duration);
                if (!seen.add(e.pkg + "|" + e.title + "|" + e.subtitle)) continue;
                out.add(e);
            }
        } catch (RuntimeException e) {
            AppLog.w("watch next: " + e.getMessage());
        }
        return out;
    }

    private static String text(Cursor cur, int i) {
        String s = cur.isNull(i) ? null : cur.getString(i);
        return s == null ? "" : s.trim();
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** The entry's image, center-cropped to w × h (null if it can't be read). Background thread. */
    static Bitmap image(Context c, String uri, int w, int h) {
        if (uri == null || uri.isEmpty()) return null;
        try {
            byte[] bytes = read(c, uri);
            if (bytes == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            return b == null ? null : crop(b, w, h);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Scaled to cover w × h and cropped in the middle. Recycles `b` when it makes a new one. */
    static Bitmap crop(Bitmap b, int w, int h) {
        float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        int sw = Math.max(w, Math.round(b.getWidth() * scale)), sh = Math.max(h, Math.round(b.getHeight() * scale));
        Bitmap scaled = Ui.downscale(b, sw, sh);
        if (scaled != b) b.recycle();
        if (sw == w && sh == h) return scaled;
        Bitmap out = Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h);
        if (out != scaled) scaled.recycle();
        return out;
    }

    private static byte[] read(Context c, String uri) throws IOException {
        Uri u = Uri.parse(uri);
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        if (scheme.equals("http") || scheme.equals("https")) {
            HttpURLConnection con = (HttpURLConnection) new URL(uri).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(10000);
            try {
                if (con.getResponseCode() != 200) return null;
                try (InputStream in = con.getInputStream()) {
                    return bytes(in);
                }
            } finally {
                con.disconnect();
            }
        }
        if (!scheme.equals(ContentResolver.SCHEME_CONTENT) && !scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE)
                && !scheme.equals(ContentResolver.SCHEME_FILE)) return null;
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            return in == null ? null : bytes(in);
        }
    }

    private static byte[] bytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 12_000_000) throw new IOException("image too large");
        }
        return out.toByteArray();
    }
}
