package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What plays right now, for the home screen's player (song, artist, ⏮ ⏯ ⏭): the media session of a music app such as
 * Spotify (music only: see music()), read with the same permission as NowPlaying (its notification listener, turned on in Free My TV mode). A
 * session shows while it plays or is paused with a title; none without the permission. On the main thread.
 */
final class MediaNow {
    interface Listener { void onMediaChanged(); }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private final List<MediaController> controllers = new ArrayList<>();
    private final List<MediaController.Callback> callbacks = new ArrayList<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::watch;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private MediaController current;
    String title, artist, pkg;
    Bitmap art;         // the cover (the app's own, then the sharper one from the web when it says where)
    boolean playing;
    long duration;      // the song's length (ms), 0 if the app doesn't say
    private PlaybackState state;

    /** Where the song is now (ms): the position the app last reported, carried forward while it plays. */
    long position() {
        PlaybackState s = state;
        if (s == null) return 0;
        long at = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            at += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0, duration > 0 ? Math.min(duration, at) : at);
    }

    MediaNow(Context c, Listener listener) {
        context = c.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        sessions = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName me = new ComponentName(context, NowPlaying.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, main);
            watch(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            sessions = null;    // the listener isn't on (Original mode): no player
        }
    }

    void stop() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        sessions = null;
        watch(null);
    }

    boolean shown() {
        return title != null;
    }

    void previous() {
        if (current != null) current.getTransportControls().skipToPrevious();
    }

    void toggle() {
        if (current == null) return;
        if (playing) current.getTransportControls().pause();
        else current.getTransportControls().play();
    }

    void next() {
        if (current != null) current.getTransportControls().skipToNext();
    }

    private void watch(List<MediaController> list) {
        for (int i = 0; i < controllers.size(); i++) controllers.get(i).unregisterCallback(callbacks.get(i));
        controllers.clear();
        callbacks.clear();
        if (list != null) {
            for (MediaController mc : list) {
                if (mc.getPackageName().equals(context.getPackageName())) continue;
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override public void onPlaybackStateChanged(PlaybackState state) { pick(); }
                    @Override public void onMetadataChanged(MediaMetadata metadata) { pick(); }
                    @Override public void onSessionDestroyed() { pick(); }
                };
                mc.registerCallback(cb, main);
                controllers.add(mc);
                callbacks.add(cb);
            }
        }
        pick();
    }

    /** The session to show: the first one playing, else the first one paused (the system lists the latest first). */
    private void pick() {
        MediaController best = null;
        boolean bestPlaying = false;
        for (MediaController mc : controllers) {
            PlaybackState s = mc.getPlaybackState();
            MediaMetadata m = mc.getMetadata();
            if (s == null || m == null || text(m, MediaMetadata.METADATA_KEY_TITLE) == null || !music(m)) continue;
            int st = s.getState();
            boolean on = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING;
            if (!on && st != PlaybackState.STATE_PAUSED) continue;
            if (best == null || (on && !bestPlaying)) {
                best = mc;
                bestPlaying = on;
            }
        }
        String t = null, a = null;
        MediaMetadata m = best == null ? null : best.getMetadata();
        if (m != null) {
            t = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            a = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
        }
        boolean newSong = !equal(t, title) || best != current;
        boolean same = !newSong && bestPlaying == playing && equal(a, artist);
        current = best;
        playing = bestPlaying;
        title = t;
        artist = a;
        pkg = best == null ? null : best.getPackageName();
        state = best == null ? null : best.getPlaybackState();
        duration = m == null ? 0 : Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
        if (updateArt(m, t)) same = false;
        if (!same) listener.onMediaChanged();
    }

    private String artUrl;
    private static final long SETTLE_MS = 800;
    private final Runnable ownArt = this::takeOwnArt;

    /** Apps that don't say where their cover is: the picture they sent, once their updates settle. */
    private void takeOwnArt() {
        MediaMetadata m = current == null ? null : current.getMetadata();
        Bitmap own = m == null ? null : m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (own == null && m != null) own = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (own == null || artUrl != null) return;
        art = own.copy(Bitmap.Config.ARGB_8888, false);
        artLoadedUrl = null;
        artOwn = true;
        listener.onMediaChanged();
        checkReady();
    }

    /** The cover shown is this song's (not the last one's): the music screensaver waits for it. */
    boolean artReady;
    private String artTitle, artLoadedUrl;
    private boolean artOwn;
    private long titleAt;
    private static final long TITLE_SETTLE_MS = 1500;
    private final Runnable settleArt = this::checkReady;

    /**
     * The cover is this song's once the app has had time to send the new song's cover after its title (two songs of
     * one album share theirs, so a cover that doesn't change counts too) and that cover has arrived.
     */
    private void checkReady() {
        if (artReady || art == null || SystemClock.uptimeMillis() - titleAt < TITLE_SETTLE_MS) return;
        boolean loaded = artUrl == null ? artOwn : artUrl.equals(artLoadedUrl);
        if (!loaded) return;
        artReady = true;
        listener.onMediaChanged();
    }

    /**
     * The song's cover. Apps send a new song's title first and its cover a moment later (Spotify, YouTube), so the cover
     * isn't tied to the title: it follows the cover's own web address (downloaded sharp; Spotify's at 640 px), the old
     * one staying until the new one arrives. Without an address, the app's picture once its updates settle. Returns
     * true if the cover is gone.
     */
    private boolean updateArt(MediaMetadata m, String song) {
        main.removeCallbacks(ownArt);
        if (m == null || song == null) {
            boolean had = art != null;
            art = null;
            artUrl = null;
            artTitle = null;
            artReady = false;
            return had;
        }
        if (!song.equals(artTitle)) {
            // A new song: its cover isn't sure yet
            artTitle = song;
            artReady = false;
            artOwn = false;
            titleAt = SystemClock.uptimeMillis();
            main.removeCallbacks(settleArt);
            main.postDelayed(settleArt, TITLE_SETTLE_MS);
        }
        final String url = NowPlaying.artUrl(text(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI));
        if (url == null) {
            artUrl = null;
            main.postDelayed(ownArt, SETTLE_MS);
            return false;
        }
        if (url.equals(artUrl)) return false;
        artUrl = url;
        LOADER.execute(() -> {
            final Bitmap big = NowPlaying.download(url);
            main.post(() -> {
                if (!url.equals(artUrl)) return;    // another song's cover is coming
                if (big == null) {
                    // Not downloaded: the app's own picture (by now, the new song's)
                    artUrl = null;
                    ownArt.run();
                    artUrl = url;
                    return;
                }
                art = big;
                artLoadedUrl = url;
                listener.onMediaChanged();
                checkReady();
            });
        });
        return false;
    }

    /**
     * Music, not a video paused in the background (Stremio, YouTube): a square cover, or without a picture an album.
     * Videos send a wide thumbnail; they belong to Explore's history (NowPlaying), not to the player.
     */
    private static boolean music(MediaMetadata m) {
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art != null) return NowPlaying.music(art);
        return text(m, MediaMetadata.METADATA_KEY_ALBUM) != null;
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence s = m.getText(k);
            if (s != null && s.toString().trim().length() > 0) return s.toString().trim();
        }
        return null;
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
