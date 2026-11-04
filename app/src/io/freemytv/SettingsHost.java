package io.freemytv;

/** What holds the settings: their own screen (SettingsActivity), or the home screen, where they open over it. */
interface SettingsHost {
    /** The screensaver preview, full screen. */
    void showPreview();

    /** Over the home screen: no background of their own (the home screen shows around the panels), beside its side bar. */
    boolean overHome();
}
