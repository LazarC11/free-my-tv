package io.freemytv;

import android.content.Context;
import android.net.ConnectivityManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver's video clips (a real fireplace, an aquarium...). Two sources:
 *  - the ones a computer put in the app's own folder on the TV (tools/videos.py: Android/data/io.freemytv/files/videos,
 *    no permission needed), played from there;
 *  - the links in config.properties (screensaver_videos), each downloaded once into the app's private folder, only on
 *    an unmetered network, one at a time in the background.
 * The screensaver plays them muted, in turn; without any, it shows photos.
 */
final class Videos {
    private Videos() {}

    private static final String[] KINDS = {".mp4", ".m4v", ".webm", ".mkv", ".mov", ".ts"};
    private static final long MAX_BYTES = 600L * 1024 * 1024;
    private static final long RETRY_MS = 30 * 60_000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile boolean busy;
    private static volatile long failedAt;

    /** The links from config.properties: https only, separated by commas, spaces or new lines. */
    static List<String> links() {
        List<String> out = new ArrayList<>();
        for (String s : Config.SCREENSAVER_VIDEOS.split("[,\\s]+")) {
            s = s.trim();
            if (s.startsWith("https://") && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private static File downloaded(Context c) {
        return new File(c.getFilesDir(), "videos");
    }

    /** Every clip ready to play: the ones sent from a computer (by name), then the downloaded ones (in link order). */
    static List<File> files(Context c) {
        List<File> out = new ArrayList<>();
        File inbox = c.getExternalFilesDir("videos");
        File[] mine = inbox == null ? null : inbox.listFiles();
        if (mine != null) {
            Arrays.sort(mine, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : mine) if (f.isFile() && video(f.getName()) && f.length() > 0) out.add(f);
        }
        for (String link : links()) {
            File f = new File(downloaded(c), name(link));
            if (f.isFile()) out.add(f);
        }
        return out;
    }

    private static boolean video(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith(".")) return false;
        for (String kind : KINDS) if (n.endsWith(kind)) return true;
        return false;
    }

    /** The file name for a link: a hash (the link itself may hold anything), with the link's extension. */
    private static String name(String link) {
        String path = link.replaceAll("[?#].*$", "");
        String ext = ".mp4";
        for (String kind : KINDS) if (path.toLowerCase(Locale.ROOT).endsWith(kind)) ext = kind;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(link.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 12; i++) b.append(String.format(Locale.ROOT, "%02x", d[i]));
            return b + ext;
        } catch (Exception e) {
            return Integer.toHexString(link.hashCode()) + ext;
        }
    }

    /** Downloads the links not on the TV yet (background; unmetered networks only), then calls `done` on any thread. */
    static void download(Context context, Runnable done) {
        final Context c = context.getApplicationContext();
        final List<String> links = links();
        if (links.isEmpty() || busy) return;
        long since = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && since >= 0 && since < RETRY_MS) return;
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || cm.isActiveNetworkMetered()) return;
        busy = true;
        IO.execute(() -> {
            boolean any = false;
            try {
                File dir = downloaded(c);
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                for (String link : links) {
                    File f = new File(dir, name(link));
                    if (f.isFile()) continue;
                    try {
                        fetch(link, f);
                        any = true;
                    } catch (IOException e) {
                        failedAt = System.currentTimeMillis();
                        AppLog.w("screensaver video: " + e.getMessage());
                    }
                }
                // Links taken out of config.properties: their files go.
                List<String> keep = new ArrayList<>();
                for (String link : links) keep.add(name(link));
                File[] all = dir.listFiles();
                if (all != null) for (File f : all) if (!keep.contains(f.getName())) f.delete();
            } finally {
                busy = false;
                if (any && done != null) done.run();
            }
        });
    }

    private static void fetch(String link, File out) throws IOException {
        File part = new File(out.getPath() + ".part");
        HttpURLConnection con = (HttpURLConnection) new URL(link).openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(30_000);
        con.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long total = 0;
            try (InputStream in = con.getInputStream(); FileOutputStream os = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("video larger than " + MAX_BYTES / (1024 * 1024) + " MB");
                    os.write(buf, 0, n);
                }
            }
            if (!part.renameTo(out)) throw new IOException("couldn't save the video");
            AppLog.w("screensaver video saved: " + total / (1024 * 1024) + " MB");
        } finally {
            con.disconnect();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }
}
