package io.freemytv;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;

import java.util.List;

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
