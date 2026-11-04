package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one. The gear in the side bar opens the settings over it (Back closes them).
 */
public class HomeActivity extends Activity implements SettingsHost {
    private HomeView home;
    private FrameLayout root;
    private SettingsActivity.SettingsView settings;   // open over the home screen, or null
    private DreamView preview;   // the screensaver preview from the settings
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        home.inputPreview = this::previewInput;
        root = new FrameLayout(this);
        root.addView(home);
        setContentView(root);
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onResume() {
        super.onResume();
        if (settings != null) {
            settings.resume();
            return;
        }
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
    }

    @Override protected void onPause() {
        closePreview();
        if (settings != null) settings.pause();
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (settings closed, focus on the first app). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closePreview();
        if (settings != null) closeSettings();
        if (home != null) home.reset(true);
    }

    @Override protected void onDestroy() {
        if (settings != null) settings.pause();
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it.
            if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
            return true;
        }
        if (settings != null) {
            if (settings.onKey(event)) return true;
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) closeSettings();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    // MARK: - Live input preview, behind the home screen

    private android.media.tv.TvView tv;
    private String tuned;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * The side bar's focused HDMI input plays behind the home screen, full screen, picture and sound (its device is
     * not woken or switched over HDMI-CEC); null stops it and lets go of the input.
     */
    private void previewInput(String inputId) {
        if (inputId == null) {
            stopPreview();
            return;
        }
        if (tv == null) {
            tv = new android.media.tv.TvView(this);
            tv.setFocusable(false);
            tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            tv.setCallback(tvCallback);
            root.addView(tv, 0);   // under the home screen, which fades its photo out over it
        }
        tv.setVisibility(View.VISIBLE);
        if (inputId.equals(tuned)) return;
        tuned = inputId;
        main.removeCallbacks(widgetGone);   // still showing: no "gone" in between
        tclHomeWidget(1);
        try {
            tv.setStreamVolume(1f);   // with its sound
            android.os.Bundle params = new android.os.Bundle();
            params.putBoolean("isCecDeviceDisable", true);   // TCL: no HDMI-CEC (doesn't wake or switch the device)
            tv.tune(inputId, android.media.tv.TvContract.buildChannelUriForPassthroughInput(inputId), params);
            AppLog.w("input preview: tuning " + inputId);
        } catch (RuntimeException e) {
            AppLog.w("input preview: " + e.getMessage());
        }
    }

    private final android.graphics.Rect clockArea = new android.graphics.Rect();
    private final Bitmap clockSample = Bitmap.createBitmap(24, 8, Bitmap.Config.ARGB_8888);

    /**
     * Every 1.5 s while an input plays: its picture behind the clock (a copy the GPU makes of the video's surface,
     * shrunk to 24 × 8), averaged, so the clock follows the input's colors. Protected video can't be copied: then the
     * clock keeps its color.
     */
    private final Runnable sampleClock = new Runnable() {
        @Override public void run() {
            if (tv == null || tuned == null || home == null) return;
            android.view.SurfaceView surface = null;
            for (int i = 0; i < tv.getChildCount(); i++) {
                if (tv.getChildAt(i) instanceof android.view.SurfaceView) surface = (android.view.SurfaceView) tv.getChildAt(i);
            }
            if (surface == null || surface.getHolder().getSurface() == null || !surface.getHolder().getSurface().isValid()) return;
            home.clockArea(clockArea);
            try {
                android.view.PixelCopy.request(surface, clockArea, clockSample, result -> {
                    if (result != android.view.PixelCopy.SUCCESS || tuned == null || home == null) return;
                    long r = 0, g = 0, b = 0;
                    int n = clockSample.getWidth() * clockSample.getHeight();
                    for (int y = 0; y < clockSample.getHeight(); y++) for (int x = 0; x < clockSample.getWidth(); x++) {
                        int p = clockSample.getPixel(x, y);
                        r += (p >> 16) & 255;
                        g += (p >> 8) & 255;
                        b += p & 255;
                    }
                    home.liveClockColor((int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n));
                }, main);
            } catch (RuntimeException e) {
                AppLog.w("input preview: clock color: " + e.getMessage());
                return;
            }
            main.postDelayed(this, 1500);
        }
    };

    private void stopPreview() {
        main.removeCallbacks(sampleClock);
        if (tv == null || tuned == null) return;
        tuned = null;
        try {
            tv.reset();
        } catch (RuntimeException e) {
            AppLog.w("input preview: " + e.getMessage());
        }
        tv.setVisibility(View.GONE);
        if (home != null) home.inputLive(false);
        // After the TV has gone back to its own source: the picture service gives the home screen its picture mode.
        main.postDelayed(widgetGone, 1000);
        AppLog.w("input preview: stopped");
    }

    private final android.media.tv.TvView.TvInputCallback tvCallback = new android.media.tv.TvView.TvInputCallback() {
        @Override public void onVideoAvailable(String inputId) {
            AppLog.w("input preview: playing " + inputId);
            // TCL marks the input's app as live TV again once its picture starts: said again now, so that the home
            // screen's picture mode comes back when the preview ends.
            tclHomeWidget(1);
            if (home != null && inputId.equals(tuned)) {
                home.inputLive(true);
                main.removeCallbacks(sampleClock);
                main.postDelayed(sampleClock, 300);
            }
        }

        @Override public void onVideoUnavailable(String inputId, int reason) {
            AppLog.w("input preview: no picture from " + inputId + " (" + reason + ")");
            if (reason == android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING) return;
            if (home != null) home.inputLive(false);
        }
    };

    /**
     * TCL: an input playing on the home screen makes its picture service take the input's picture mode, and keep it
     * after the preview ends. TCL's own home screen, with its live widget, tells the service when its preview shows (1)
     * and goes (0): option 38 of TvPqManager.setOption. The same here, in order, off the main thread. Not a TCL: nothing.
     */
    private static void tclHomeWidget(int status) {
        PQ.execute(() -> {
            try {
                Class<?> pq = Class.forName("com.tcl.tv.pq.TvPqManager");
                Object manager = pq.getMethod("getInstance").invoke(null);
                if (manager == null) return;
                Object ok = pq.getMethod("setOption", int.class, int.class, String.class)
                        .invoke(manager, 38, status, String.valueOf(status));
                AppLog.w("input preview: home widget " + status + " -> " + ok);
            } catch (ClassNotFoundException e) {
                // not a TCL TV
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                AppLog.w("input preview: home widget " + status + ": " + e);
            }
        });
    }

    private static final java.util.concurrent.ExecutorService PQ = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable widgetGone = () -> tclHomeWidget(0);

    // MARK: - Settings over the home screen

    /** The gear in the side bar: the settings open over the home screen, which waits behind them. */
    void openSettings() {
        if (settings != null || home == null) return;
        final SettingsActivity.SettingsView s = new SettingsActivity.SettingsView(this, this);
        settings = s;
        root.addView(s);
        home.closeRail();   // the open side bar closes: the panels sit beside the slim one
        home.pause();       // still on screen behind the panels, just not animating
        s.resume();
        s.requestFocus();
        // The panels come out of the side bar's gear: from its left, a little smaller, fading in. Nothing behind is
        // blurred or hidden: the home screen stays as it is around them.
        float k = root.getWidth() / 1920f;
        s.setPivotX(116 * k);
        s.setPivotY(684 * k);
        s.setAlpha(0);
        s.setScaleX(0.94f);
        s.setScaleY(0.94f);
        s.setTranslationX(-40 * k);
        s.animate().alpha(1).scaleX(1).scaleY(1).translationX(0).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.6f));
    }

    private void closeSettings() {
        final SettingsActivity.SettingsView s = settings;
        settings = null;
        s.pause();
        home.setVisibility(View.VISIBLE);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            root.removeView(s);
            HomeData.get(this).settingsChanged();
            recreate();   // a new language, city or look: draw everything again
            return;
        }
        home.resume();
        // Back into the side bar's gear.
        float k = root.getWidth() / 1920f;
        s.animate().cancel();
        s.animate().alpha(0).scaleX(0.94f).scaleY(0.94f).translationX(-40 * k).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> root.removeView(s));
    }

    @Override public boolean overHome() {
        return true;
    }

    @Override public void showPreview() {
        preview = new DreamView(this);
        root.addView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        root.removeView(preview);
        preview = null;
        if (settings != null) settings.resume();
    }
}
