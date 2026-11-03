package io.freemytv;

import android.service.notification.NotificationListenerService;

/**
 * Only there so the home screen may read which app is playing (NowPlaying: MediaSessionManager wants an allowed
 * notification listener). It reads no notifications. Allowed in Free My TV mode, taken back by Original.
 */
public class MediaListener extends NotificationListenerService {
}
