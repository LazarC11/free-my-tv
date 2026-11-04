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
