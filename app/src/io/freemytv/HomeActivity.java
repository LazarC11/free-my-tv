package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Settings > Liquid Glass: the home screen with its Liquid Glass panel open (the look changes live). */
    static final String ACTION_GLASS = "io.freemytv.action.LIQUID_GLASS";

    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    // With music playing, the screensaver comes after 10 s without a key (the system's own waits minutes, and only
    // the system may start it): the same view, over the home screen. Any key brings the home screen back.
    private static final long MUSIC_SAVER_MS = 10_000;
    private DreamView saver;
    private boolean saverOn, resumed;
    private long keyAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            if (!resumed || saverOn) return;
            if (SystemClock.uptimeMillis() - keyAt >= MUSIC_SAVER_MS && home.musicPlaying()) showSaver();
            else handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("O Início do Free My TV teve um problema.\nVoltando para o Início original da TV…",
                    "Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        saver = new DreamView(this);
        saver.setVisibility(View.GONE);
        FrameLayout root = new FrameLayout(this);
        root.addView(home);
        root.addView(saver);
        setContentView(root);
        getWindow().setBackgroundDrawable(null);
        if (ACTION_GLASS.equals(getIntent().getAction())) home.openGlassPanel();
    }

    private void showSaver() {
        saverOn = true;
        home.pause();
        saver.setAlpha(0);
        saver.setVisibility(View.VISIBLE);
        saver.start();
        saver.animate().alpha(1).setDuration(700).start();
    }

    private void hideSaver() {
        saverOn = false;
        saver.animate().cancel();
        saver.stop();
        saver.setVisibility(View.GONE);
        home.resume();
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        resumed = true;
        keyAt = SystemClock.uptimeMillis();
        if (home != null) handler.postDelayed(idleCheck, 1000);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(idleCheck);
        if (saverOn) {
            saverOn = false;
            saver.stop();
            saver.setVisibility(View.GONE);
        } else if (home != null) {
            home.pause();
        }
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        keyAt = SystemClock.uptimeMillis();
        if (saverOn) hideSaver();
        if (home == null) return;
        if (ACTION_GLASS.equals(intent.getAction())) home.openGlassPanel();
        else home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        keyAt = SystemClock.uptimeMillis();
        if (saverOn) {
            // Any key only wakes the home screen (as the screensaver does); the system's own keys still work
            if (isSystemKey(event.getKeyCode())) return super.dispatchKeyEvent(event);
            if (event.getAction() == KeyEvent.ACTION_UP) {
                hideSaver();
                handler.removeCallbacks(idleCheck);
                handler.postDelayed(idleCheck, 1000);
            }
            return true;
        }
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private static boolean isSystemKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_POWER:
                return true;
            default:
                return false;
        }
    }

}
