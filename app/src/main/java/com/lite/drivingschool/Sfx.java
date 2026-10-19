package com.lite.drivingschool;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Procedural sound: every sample is synthesised in code at start-up (about 150 KB of RAM,
 * 0 bytes of storage). The engine is a short looping waveform whose playback rate is
 * changed live to follow the revs, so one tiny buffer gives a full pitch range.
 */
final class Sfx {
    static final int RATE = 22050;
    static final int TICK = 0, TOCK = 1, CRASH = 2, BEEP = 3, GEAR = 4, HORN = 5,
            WIN = 6, FAIL = 7, CLICK = 8, COUNT = 9;
    static final int ENG_N = 8000;                       // 20 periods of 400 samples
    static final float[] VOL = {0.5f, 0.45f, 1f, 0.6f, 0.7f, 0.8f, 0.7f, 0.7f, 0.35f};

    boolean muted;
    private AudioTrack eng;
    private final AudioTrack[] fx = new AudioTrack[COUNT];
    private boolean engPlaying;
    private int lastRate;
    private float lastVol = -1f;

    Sfx() {
        try {
            eng = track(engineLoop());
            if (eng != null) eng.setLoopPoints(0, ENG_N, -1);
        } catch (Throwable t) {
            eng = null;
        }
        for (int i = 0; i < COUNT; i++) {
            try {
                fx[i] = track(make(i));
            } catch (Throwable t) {
                fx[i] = null;
            }
        }
    }

    private static AudioTrack track(short[] d) {
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, d.length * 2, AudioTrack.MODE_STATIC);
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            return null;
        }
        t.write(d, 0, d.length);
        return t;
    }

    // ------------------------------------------------------------ control
    void setMuted(boolean m) {
        muted = m;
        if (m) engine(false, 1f, 0f);
    }

    /** Engine on/off. rate = pitch multiplier (1.0 = about 55 Hz), vol = 0..1. */
    void engine(boolean on, float rate, float vol) {
        if (eng == null) return;
        try {
            if (!on || muted) {
                if (engPlaying) {
                    eng.pause();
                    engPlaying = false;
                }
                return;
            }
            if (!engPlaying) {
                eng.play();
                engPlaying = true;
            }
            int r = (int) (RATE * rate);
            if (Math.abs(r - lastRate) > RATE / 100) {
                eng.setPlaybackRate(r);
                lastRate = r;
            }
            if (Math.abs(vol - lastVol) > 0.01f) {
                eng.setVolume(vol);
                lastVol = vol;
            }
        } catch (Throwable t) {
            // audio is optional - never crash the game
        }
    }

    void play(int id) {
        if (muted || id < 0 || id >= COUNT) return;
        AudioTrack t = fx[id];
        if (t == null) return;
        try {
            t.stop();
            t.reloadStaticData();
            t.setVolume(VOL[id]);
            t.play();
        } catch (Throwable e) {
            // ignore
        }
    }

    void release() {
        try {
            if (eng != null) eng.release();
        } catch (Throwable t) { /* ignore */ }
        eng = null;
        for (int i = 0; i < COUNT; i++) {
            try {
                if (fx[i] != null) fx[i].release();
            } catch (Throwable t) { /* ignore */ }
            fx[i] = null;
        }
    }

    // ------------------------------------------------------------ synthesis
    private static short[] engineLoop() {
        float[] b = new float[ENG_N];
        java.util.Random r = new java.util.Random(7);
        float peak = 0.0001f;
        for (int i = 0; i < ENG_N; i++) {
            double ph = 2.0 * Math.PI * i / 400.0;
            double v = 0;
            for (int k = 1; k <= 9; k++) v += Math.sin(k * ph + k * 0.7) / (k * 0.9 + 0.1);
            v *= 0.78 + 0.22 * Math.sin(2.0 * Math.PI * i / 800.0 + 1.0);   // cylinder pulse
            v += (r.nextFloat() - 0.5f) * 0.35f;
            b[i] = (float) v;
            peak = Math.max(peak, Math.abs(b[i]));
        }
        for (int i = 0; i < ENG_N; i++) {
            float x = b[i] / peak * 1.4f;
            b[i] = x / (1f + Math.abs(x)) * 1.2f;            // soft clip
        }
        return pcm(b);
    }

    private static short[] make(int id) {
        float[] b;
        switch (id) {
            case TICK:
                b = new float[(int) (0.04f * RATE)];
                tone(b, 0f, 2400f, 0.035f, 0.8f, 70f, 0.3f);
                noise(b, 0f, 0.01f, 0.25f, 100f, 0.9f, 1);
                break;
            case TOCK:
                b = new float[(int) (0.04f * RATE)];
                tone(b, 0f, 1700f, 0.035f, 0.8f, 70f, 0.3f);
                break;
            case CLICK:
                b = new float[(int) (0.035f * RATE)];
                tone(b, 0f, 1300f, 0.03f, 0.7f, 80f, 0.2f);
                break;
            case GEAR:
                b = new float[(int) (0.16f * RATE)];
                tone(b, 0f, 95f, 0.14f, 1.0f, 22f, 0.5f);
                noise(b, 0f, 0.05f, 0.5f, 60f, 0.6f, 2);
                break;
            case CRASH:
                b = new float[(int) (0.75f * RATE)];
                noise(b, 0f, 0.7f, 1.6f, 6f, 0.25f, 3);
                noise(b, 0f, 0.15f, 0.8f, 30f, 0.9f, 4);
                tone(b, 0f, 62f, 0.55f, 0.9f, 7f, 0.3f);
                break;
            case BEEP:
                b = new float[(int) (0.45f * RATE)];
                tone(b, 0f, 700f, 0.14f, 0.45f, 0f, 0.8f);
                tone(b, 0.17f, 450f, 0.24f, 0.45f, 0f, 0.8f);
                break;
            case HORN:
                b = new float[(int) (0.5f * RATE)];
                tone(b, 0f, 420f, 0.5f, 0.28f, 0f, 1.0f);
                tone(b, 0f, 530f, 0.5f, 0.28f, 0f, 1.0f);
                break;
            case WIN:
                b = new float[(int) (0.85f * RATE)];
                tone(b, 0.00f, 523f, 0.25f, 0.35f, 5f, 0.4f);
                tone(b, 0.14f, 659f, 0.25f, 0.35f, 5f, 0.4f);
                tone(b, 0.28f, 784f, 0.25f, 0.35f, 5f, 0.4f);
                tone(b, 0.42f, 1047f, 0.40f, 0.35f, 4f, 0.4f);
                break;
            default:
                b = new float[(int) (0.9f * RATE)];
                tone(b, 0.00f, 392f, 0.35f, 0.40f, 4f, 0.8f);
                tone(b, 0.20f, 330f, 0.35f, 0.40f, 4f, 0.8f);
                tone(b, 0.40f, 262f, 0.45f, 0.40f, 4f, 0.8f);
                break;
        }
        return pcm(b);
    }

    private static void tone(float[] b, float t0, float f, float dur, float amp, float decay, float harm) {
        int s = (int) (t0 * RATE), n = (int) (dur * RATE);
        for (int i = 0; i < n && s + i < b.length; i++) {
            float t = i / (float) RATE;
            float env = (float) Math.exp(-decay * t);
            float att = Math.min(1f, i / (RATE * 0.004f));
            float rel = Math.min(1f, (n - i) / (RATE * 0.008f));
            double ph = 2.0 * Math.PI * f * t;
            double v = Math.sin(ph) + harm * 0.5 * Math.sin(2 * ph) + harm * 0.33 * Math.sin(3 * ph);
            b[s + i] += (float) (amp * env * att * rel * v);
        }
    }

    private static void noise(float[] b, float t0, float dur, float amp, float decay, float lp, long seed) {
        java.util.Random r = new java.util.Random(seed);
        int s = (int) (t0 * RATE), n = (int) (dur * RATE);
        float y = 0f;
        for (int i = 0; i < n && s + i < b.length; i++) {
            float x = r.nextFloat() * 2f - 1f;
            y += lp * (x - y);
            float rel = Math.min(1f, (n - i) / (RATE * 0.008f));
            b[s + i] += amp * (float) Math.exp(-decay * i / (float) RATE) * y * rel;
        }
    }

    private static short[] pcm(float[] b) {
        short[] o = new short[b.length];
        for (int i = 0; i < b.length; i++) {
            float v = b[i];
            if (v > 1f) v = 1f;
            if (v < -1f) v = -1f;
            o[i] = (short) (v * 30000f);
        }
        return o;
    }
}
