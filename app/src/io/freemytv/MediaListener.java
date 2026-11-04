package io.freemytv;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the home screen read which app is playing (NowPlaying: MediaSessionManager wants an allowed notification
 * listener), and while the TV is on follows the video apps' sessions for Continue watching (VideoHistory). It reads
 * no notifications. Allowed in Free My TV mode, taken back by Original.
 */
public class MediaListener extends NotificationListenerService {
    private MediaSessionManager sessions;
    private final List<MediaController> controllers = new ArrayList<>();
    private final List<MediaController.Callback> callbacks = new ArrayList<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::watch;

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        try {
            sessions = getSystemService(MediaSessionManager.class);
            ComponentName me = new ComponentName(this, MediaListener.class);
            sessions.addOnActiveSessionsChangedListener(changed, me);
            watch(sessions.getActiveSessions(me));
        } catch (RuntimeException e) {
            sessions = null;
            AppLog.w("media listener: " + e.getMessage());
        }
    }

    @Override public void onListenerDisconnected() {
        if (sessions != null) {
            try {
                sessions.removeOnActiveSessionsChangedListener(changed);
            } catch (RuntimeException ignored) {
            }
        }
        sessions = null;
        watch(null);
        super.onListenerDisconnected();
    }

    /** Follows every session but ours; each change goes to VideoHistory (which keeps only the videos). */
    private void watch(List<MediaController> list) {
        for (int i = 0; i < controllers.size(); i++) controllers.get(i).unregisterCallback(callbacks.get(i));
        controllers.clear();
        callbacks.clear();
        if (list == null) return;
        for (MediaController mc : list) {
            if (mc.getPackageName().equals(getPackageName())) continue;
            // Apps change a new video's title first and its picture and ID a moment later (YouTube): read the session
            // once its changes have settled, else the new title gets the last video's picture.
            final Runnable read = () -> VideoHistory.seen(MediaListener.this, mc);
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState state) { settle(read); }
                @Override public void onMetadataChanged(MediaMetadata metadata) { settle(read); }
            };
            mc.registerCallback(cb, handler);
            controllers.add(mc);
            callbacks.add(cb);
            settle(read);
        }
    }

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Runs `read` once the session has been quiet for 2.5 s (each change starts the wait again). */
    private void settle(Runnable read) {
        handler.removeCallbacks(read);
        handler.postDelayed(read, 2500);
    }
}
