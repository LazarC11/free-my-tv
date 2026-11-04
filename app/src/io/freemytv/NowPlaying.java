package io.freemytv;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What's playing now (music, a podcast) for the home screen's card: the media session of any app that is playing.
 * Reading other apps' sessions needs a notification listener (MediaListener), which the switch to Free My TV mode
 * allows over ADB (`cmd notification allow_listener`); without it, or on low-memory TVs where Android refuses
 * listeners, there is simply no card.
 */
final class NowPlaying {
    interface Listener { void onNowPlaying(); }

    /** One snapshot of the playing session, made on the main thread when it changes. */
    static final class Track {
        String title = "", artist = "", app = "", pkg;
        Bitmap art;
        long position = -1, duration = -1, positionAt;   // ms; positionAt: elapsedRealtime of the position
        float speed = 1;
        boolean playing = true;
        PendingIntent open;

        /** The position now, moving with the playback (-1 when unknown). */
        long positionNow() {
            if (position < 0) return -1;
            long p = position + (long) ((SystemClock.elapsedRealtime() - positionAt) * speed);
            return duration > 0 ? Math.min(duration, p) : p;
        }
    }

    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final ComponentName component;
    private MediaSessionManager manager;
    private MediaController controller;
    private Track track;
    private boolean running;
    // Sharp covers by web address (Spotify's at 640 px), the last few songs; the one downloading now.
    private static final ExecutorService COVERS = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> covers = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 12; }
    };
    private final Set<String> downloading = new HashSet<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = list -> pick(list);

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { refresh(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { refresh(); }
        @Override public void onSessionDestroyed() { pick(null); }
    };

    NowPlaying(Context c, Handler handler, Listener listener) {
        context = c.getApplicationContext();
        this.handler = handler;
        this.listener = listener;
        component = new ComponentName(context, MediaListener.class);
    }

    /** The track playing now, or null. */
    Track track() {
        return track;
    }

    void start() {
        if (running) return;
        running = true;
        try {
            manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return;
            manager.addOnActiveSessionsChangedListener(sessions, component, handler);
            pick(manager.getActiveSessions(component));
        } catch (SecurityException e) {
            manager = null;     // the listener isn't allowed: no card
        } catch (RuntimeException e) {
            manager = null;
            AppLog.w("now playing: " + e.getMessage());
        }
    }

    void stop() {
        if (!running) return;
        running = false;
        if (manager != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessions);
            } catch (RuntimeException ignored) {
            }
        }
        follow(null);
    }

    /** The first session that is playing (or about to); the others are ignored. */
    private void pick(List<MediaController> list) {
        if (!running) return;
        if (list == null && manager != null) {
            try {
                list = manager.getActiveSessions(component);
            } catch (RuntimeException e) {
                list = null;
            }
        }
        MediaController best = null;
        if (list != null) {
            for (MediaController m : list) {
                if (playing(m)) {
                    best = m;
                    break;
                }
            }
            // Nothing playing: the paused one we follow stays.
            if (best == null && controller != null && paused(controller)) {
                for (MediaController m : list) if (m.getSessionToken().equals(controller.getSessionToken())) best = controller;
            }
        }
        follow(best);
    }

    private static boolean playing(MediaController m) {
        PlaybackState s = m.getPlaybackState();
        if (s == null) return false;
        int state = s.getState();
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_SKIPPING_TO_NEXT || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS;
    }

    private void follow(MediaController m) {
        if (controller != null && (m == null || !controller.getSessionToken().equals(m.getSessionToken()))) {
            controller.unregisterCallback(callback);
            controller = null;
        }
        if (m != null && controller == null) {
            controller = m;
            controller.registerCallback(callback, handler);
        }
        refresh();
    }

    /** A paused session stays (the home screen's pause button must not make the music go away); a stopped one goes. */
    private static boolean paused(MediaController m) {
        PlaybackState s = m.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PAUSED;
    }

    /** The remote's play / pause, next and previous, sent to the app that plays. */
    void playPause() {
        if (controller == null) return;
        if (playing(controller)) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    void next() {
        if (controller != null) controller.getTransportControls().skipToNext();
    }

    void previous() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
    }

    /** Reads the followed session again; a session that stopped hides the music (another may be playing). */
    private void refresh() {
        Track next = null;
        if (controller != null && !playing(controller) && !paused(controller)) {
            controller.unregisterCallback(callback);
            controller = null;
            handler.post(() -> pick(null));
        }
        if (controller != null) next = read(controller);
        // Videos (YouTube, Stremio…) are Continue watching's (VideoHistory), not the music card.
        if (next != null && VideoHistory.isVideo(next.pkg, next.art)) next = null;
        if (same(track, next)) {
            if (track != null && next != null) {
                boolean changed = track.playing != next.playing;
                track.position = next.position;
                track.positionAt = next.positionAt;
                track.speed = next.speed;
                track.playing = next.playing;
                if (changed) listener.onNowPlaying();
            }
            return;
        }
        track = next;
        listener.onNowPlaying();
    }

    private Track read(MediaController m) {
        Track t = new Track();
        t.pkg = m.getPackageName();
        MediaMetadata md = m.getMetadata();
        if (md != null) {
            t.title = first(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            t.artist = first(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
            t.duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (t.duration <= 0) t.duration = -1;
            t.art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (t.art == null) t.art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (t.art == null) t.art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            // Apps send a new song's title first and its picture a moment later (Spotify): the cover follows its
            // web address instead, downloaded sharp; until it arrives, the app's picture (or the last cover) stays.
            String url = artUrl(first(md, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI));
            if (url != null) {
                Bitmap sharp = covers.get(url);
                if (sharp != null) t.art = sharp;
                else {
                    if (t.art == null && track != null) t.art = track.art;
                    download(url);
                }
            }
        }
        PlaybackState s = m.getPlaybackState();
        if (s != null && s.getPosition() >= 0) {
            t.position = s.getPosition();
            t.positionAt = s.getLastPositionUpdateTime() > 0 ? s.getLastPositionUpdateTime() : SystemClock.elapsedRealtime();
            t.speed = s.getState() == PlaybackState.STATE_PLAYING ? (s.getPlaybackSpeed() == 0 ? 1 : s.getPlaybackSpeed()) : 0;
        }
        t.playing = playing(m);
        t.open = m.getSessionActivity();
        try {
            PackageManager pm = context.getPackageManager();
            t.app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(t.pkg, 0)));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            t.app = "";
        }
        if (t.title.isEmpty()) t.title = t.app;
        return t;
    }

    /** Downloads a cover once (in the background), then reads the session again so the card takes it. */
    private void download(String url) {
        if (!downloading.add(url)) return;
        COVERS.execute(() -> {
            Bitmap b = null;
            try {
                byte[] bytes = DailyPhoto.get(url);
                b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception e) {
                AppLog.w("cover: " + e.getMessage());
            }
            final Bitmap got = b;
            handler.post(() -> {
                downloading.remove(url);
                if (got == null) return;
                covers.put(url, got);
                if (running) refresh();
            });
        });
    }

    /** Where to download the cover: web addresses as they are, Spotify's own (spotify:image:…) at 640 px; or null. */
    static String artUrl(String uri) {
        if (uri == null || uri.isEmpty()) return null;
        if (uri.startsWith("https://") || uri.startsWith("http://")) return uri;
        if (uri.startsWith("spotify:image:")) {
            String id = uri.substring("spotify:image:".length());
            // Album covers: ab67616d + size (00004851 = 64, 00001e02 = 300, 0000b273 = 640) + the image's own ID
            if (id.startsWith("ab67616d") && id.length() > 16) id = "ab67616d0000b273" + id.substring(16);
            return "https://i.scdn.co/image/" + id;
        }
        return null;
    }

    private static String first(MediaMetadata md, String... keys) {
        for (String key : keys) {
            CharSequence v = md.getText(key);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return "";
    }

    private static boolean same(Track a, Track b) {
        if (a == null || b == null) return a == b;
        return a.title.equals(b.title) && a.artist.equals(b.artist) && a.pkg.equals(b.pkg) && a.art == b.art
                && a.duration == b.duration;
    }

    /** OK on the card: the app's own screen for what's playing, else the app. */
    static boolean open(Context c, Track t) {
        if (t == null) return false;
        if (t.open != null) {
            try {
                t.open.send();
                return true;
            } catch (PendingIntent.CanceledException ignored) {
            }
        }
        Intent launch = HomeApps.launchIntent(c.getPackageManager(), t.pkg);
        if (launch == null) return false;
        try {
            c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
