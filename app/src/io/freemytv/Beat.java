package io.freemytv;

import android.media.audiofx.Visualizer;

/**
 * The equalizer bars' heights from what the TV is playing: Android's Visualizer on the output mix (session 0), the sound
 * that goes to the speakers, never the microphone. It needs the RECORD_AUDIO permission (granted through ADB in Free My
 * TV mode); without it, or where the TV gives only silence, `live()` is false and the bars keep their own animation.
 * On only while bars are on screen.
 */
final class Beat {
    private static final long SILENT_MS = 3000;

    private final int bars, captureSize;
    private final int[][] bands;    // FFT bins per bar, low to high, spaced like hearing (narrow in the bass)
    private Visualizer visualizer;
    private final float[] level;    // 0 to 1, smoothed
    private final float[] peak;     // each bar's recent loudest, so quiet songs still move
    private volatile long heardAt;

    /** `bars` bands from the bass to ~11 kHz: a few (the home screen's 4) or many (the screensaver's wide row). */
    Beat(int bars) {
        this.bars = bars;
        level = new float[bars];
        peak = new float[bars];
        java.util.Arrays.fill(peak, 0.3f);
        // Many bars need finer bins: 1024 samples at 44.1 kHz is ~43 Hz a bin; 256 is ~172 Hz
        captureSize = bars > 8 ? 1024 : 256;
        double lo = bars > 8 ? 2 : 1, hi = captureSize / 4.0;
        bands = new int[bars][2];
        for (int b = 0; b < bars; b++) {
            int from = (int) Math.round(lo * Math.pow(hi / lo, b / (double) bars));
            int to = (int) Math.round(lo * Math.pow(hi / lo, (b + 1) / (double) bars)) - 1;
            bands[b][0] = from;
            bands[b][1] = Math.max(from, to);
        }
    }

    /** Starts listening (does nothing if it already is or isn't allowed). */
    synchronized void start() {
        if (visualizer != null) return;
        try {
            Visualizer v = new Visualizer(0);
            v.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.max(range[0], Math.min(captureSize, range[1])));
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer vis, byte[] waveform, int rate) {}
                @Override public void onFftDataCapture(Visualizer vis, byte[] fft, int rate) { take(fft); }
            }, Math.min(Visualizer.getMaxCaptureRate(), 20_000), false, true);
            v.setEnabled(true);
            visualizer = v;
        } catch (RuntimeException e) {     // no permission, or the TV doesn't offer it
            AppLog.w("equalizer from the sound unavailable: " + e.getMessage());
        }
    }

    synchronized void stop() {
        if (visualizer == null) return;
        try {
            visualizer.setEnabled(false);
            visualizer.release();
        } catch (RuntimeException ignored) {}
        visualizer = null;
    }

    /** Sound was heard lately: the bars follow it (otherwise they animate on their own). */
    boolean live() {
        return visualizer != null && System.currentTimeMillis() - heardAt < SILENT_MS;
    }

    /** Bar `i`'s height now, 0 to 1. */
    float level(int i) {
        return level[i];
    }

    /** One FFT (real and imaginary bytes per bin): each band's loudness, against its recent peak, eased. */
    private void take(byte[] fft) {
        boolean sound = false;
        for (int b = 0; b < bars; b++) {
            float sum = 0;
            int from = Math.min(bands[b][0], fft.length / 2 - 1), to = Math.min(bands[b][1], fft.length / 2 - 1);
            for (int i = from; i <= to; i++) {
                float re = fft[2 * i], im = fft[2 * i + 1];
                sum += (float) Math.sqrt(re * re + im * im);
            }
            float mag = sum / Math.max(1, to - from + 1);
            if (mag > 1.5f) sound = true;
            peak[b] = Math.max(mag, peak[b] * 0.995f);
            float target = Math.min(1, mag / Math.max(4, peak[b]));
            // Up fast, down slower (like a meter)
            level[b] += (target - level[b]) * (target > level[b] ? 0.6f : 0.25f);
        }
        if (sound) heardAt = System.currentTimeMillis();
    }
}
