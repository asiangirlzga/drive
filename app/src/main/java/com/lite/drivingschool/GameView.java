package com.lite.drivingschool;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Lite Driving School - one custom View, no bitmaps, no audio, no libraries.
 * Pseudo-3D road is projected from road segments and drawn with plain Canvas shapes,
 * so RAM and storage use stay tiny. Everything is drawn from the driver's seat.
 *
 * Remote:  UP = accelerator (hold)   DOWN = brake (hold)   LEFT/RIGHT = steering
 *          OK = shift gear (when stopped) / select in menus   BACK = pause
 *          1 or RED = left indicator   3 or GREEN = right indicator   2 or YELLOW = hazard
 */
public class GameView extends View {

    // ---------------------------------------------------------------- constants
    static final int SEG = 200;            // segment length (world units)
    static final int T = 1800;             // segments in the loop
    static final int NC = 40;              // max traffic cars
    static final int MAXD = 110;           // max draw distance (segments)
    static final int NMIN = 3;             // first segment that is drawn (hidden under dashboard)
    static final int CARL = 900;           // car length
    static final int NOSE = 900;           // distance from "pos" to the bonnet tip
    static final float TLEN = SEG * (float) T;
    static final float RW = 2000f;         // road half width
    static final float CAMH = 1000f;       // camera height
    static final float DEPTH = 0.84f;      // camera depth (fov ~100 deg)
    static final float CAMBACK = 840f;     // camera sits this far behind "pos"
    static final float KU = 60f;           // world units per second per km/h
    static final float MAXK = 120f;        // top speed km/h
    static final float MPS = 3f;           // metres per segment (labels only)

    static final int S_MENU = 0, S_PLAY = 1, S_PAUSE = 2, S_RESULT = 3;
    static final int G_P = 0, G_R = 1, G_N = 2, G_D = 3;
    static final int[] NEXT_GEAR = {G_D, G_N, G_P, G_R};
    static final String[] GEARS = {"P", "R", "N", "D"};
    static final int[] LIM = {40, 60, 50, 30, 60, 40};            // speed limit per 300 segments
    static final float[] LANEX = {-0.667f, 0f, 0.667f};
    static final float[] LANEK = {25f, 40f, 55f};                 // traffic speed per lane
    static final int[] PAL = {0xFF8B7765, 0xFF6D7A86, 0xFFA08F7E, 0xFF566372, 0xFFB3A38F, 0xFF7C6A62};
    static final int[] CARC = {0xFFC0392B, 0xFF2E86C1, 0xFFF4F4F4, 0xFFF1C40F, 0xFF27AE60, 0xFF7D3C98, 0xFF95A5A6};
    static final int[] SIGS = {250, 550, 850, 1150, 1450, 1750};   // traffic signal segments
    static final int[] PUMPS = {780, 1560};                        // petrol pump segments
    static final String[] SPD_LBL = {"0", "40", "80", "120", "160"};

    static final float[] CURVE = new float[T];
    static final byte[] FEAT = new byte[T];

    static {
        java.util.Random r = new java.util.Random(11);
        int i = 120;
        while (i < T - 200) {
            i += 60 + r.nextInt(100);
            int len = 80 + r.nextInt(100);
            float mag = (1.3f + r.nextFloat() * 2.2f) * (r.nextBoolean() ? 1f : -1f);
            for (int k = 0; k < len && i < T - 120; k++, i++) {
                CURVE[i] = (float) (mag * Math.sin(Math.PI * k / len));
            }
        }
        for (int k = 0; k < SIGS.length; k++) FEAT[SIGS[k]] = (byte) (k + 1);
        for (int p : PUMPS) {
            FEAT[p] = 10;
            FEAT[p + 4] = 10;
            FEAT[p + 8] = 10;
        }
    }

    static int hash(int n) {
        n = (n ^ 61) ^ (n >>> 16);
        n += n << 3;
        n ^= n >>> 4;
        n *= 0x27d4eb2d;
        n ^= n >>> 15;
        return n;
    }

    static int shade(int c, float f) {
        return 0xFF000000 | ((int) (((c >> 16) & 255) * f) << 16)
                | ((int) (((c >> 8) & 255) * f) << 8) | (int) ((c & 255) * f);
    }

    // ---------------------------------------------------------------- state
    int state = S_MENU, sel = 0;
    boolean test = true, low = true;
    float pos, speed = 25f, playerX, steer, fuel = 1f, score = 100f, time, clock, traveled;
    int gear = G_D;
    boolean gas, brk, left, right, blinkL, blinkR, haz;
    float blinkT, overT, offT, crashCd, msgT;
    int lastLane = 1, lastZone = -1;
    String msg = "", hint = "";
    boolean msgBad;
    String resTitle = "";
    boolean resPass;

    // traffic
    int nc;
    final float[] cz = new float[NC], cs = new float[NC], ct = new float[NC];
    final int[] cl = new int[NC], cc = new int[NC], cn = new int[NC], nextC = new int[NC];
    final boolean[] cb = new boolean[NC];

    // scene scratch arrays
    final float[] X = new float[MAXD + 2], W = new float[MAXD + 2], Y = new float[MAXD + 2], SC = new float[MAXD + 2];
    final int[] head = new int[MAXD + 2];

    // drawing
    int w = 1, h = 1;
    float vs = 1f;
    Canvas cv;
    final Paint pf = new Paint();            // scene (no AA)
    final Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG);   // HUD
    final Paint skyP = new Paint(), hazeP = new Paint();
    final Path path = new Path();
    final RectF rf = new RectF();
    final StringBuilder sb = new StringBuilder(32);
    long last;
    final Sfx sfx = new Sfx();
    float rpm = 0.7f;
    int lastBlink = -1;
    int shownSpd = -1;
    String shownSpdS = "0";
    float itemTop, itemStep;

    public GameView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        pf.setStyle(Paint.Style.FILL);
        spawnTraffic();
        last = System.nanoTime();
    }

    // ---------------------------------------------------------------- lifecycle
    public void pauseGame() {
        if (state == S_PLAY) {
            state = S_PAUSE;
            sel = 0;
            clearInput();
        }
    }

    public void resumeClock() {
        last = System.nanoTime();
    }

    public void soundPause() {
        sfx.engine(false, 1f, 0f);
    }

    public void soundRelease() {
        sfx.release();
    }

    /** Engine note follows revs; indicator ticks follow the dashboard light. */
    private void audio(float dt) {
        if (state != S_PLAY || fuel <= 0f) {
            sfx.engine(false, 1f, 0f);
            rpm = 0.7f;
            lastBlink = -1;
            return;
        }
        float sp = Math.min(1f, Math.abs(speed) / MAXK);
        float target = 0.7f + 2.2f * sp;
        if (gas) target = (gear == G_P || gear == G_N) ? 2.3f : target + 0.25f;
        rpm += (target - rpm) * Math.min(1f, dt * 4f);
        sfx.engine(true, rpm, 0.22f + (gas ? 0.28f : 0f) + 0.2f * sp);
        if (blinkL || blinkR || haz) {
            int ph = (int) (clock * 2.5f) & 1;
            if (ph != lastBlink) {
                lastBlink = ph;
                sfx.play(ph == 0 ? Sfx.TICK : Sfx.TOCK);
            }
        } else {
            lastBlink = -1;
        }
    }

    public boolean back() {
        switch (state) {
            case S_PLAY:
                pauseGame();
                return true;
            case S_PAUSE:
                state = S_PLAY;
                last = System.nanoTime();
                return true;
            case S_RESULT:
                backToMenu();
                return true;
            default:
                return false;
        }
    }

    void clearInput() {
        gas = brk = left = right = false;
    }

    private void backToMenu() {
        state = S_MENU;
        sel = 0;
        speed = 25f;
        playerX = 0f;
        gear = G_D;
        steer = 0f;
        clearInput();
        blinkL = blinkR = haz = false;
    }

    private void startGame(boolean t) {
        test = t;
        state = S_PLAY;
        pos = 0f;
        speed = 0f;
        playerX = 0f;
        steer = 0f;
        gear = G_P;
        fuel = 1f;
        score = 100f;
        time = 0f;
        traveled = 0f;
        msg = "";
        msgT = 0f;
        hint = "";
        blinkL = blinkR = haz = false;
        overT = offT = crashCd = blinkT = 0f;
        lastLane = 1;
        lastZone = -1;
        clearInput();
        spawnTraffic();
        last = System.nanoTime();
    }

    private void finish(String title, boolean pass) {
        state = S_RESULT;
        sel = 0;
        resTitle = title;
        resPass = pass;
        sfx.play(pass ? Sfx.WIN : Sfx.FAIL);
        clearInput();
    }

    // ---------------------------------------------------------------- input
    private int action(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_BUTTON_R2:
                return 1;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_S:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_BUTTON_L2:
                return 2;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_A:
                return 3;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_D:
                return 4;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_SPACE:
                return 5;
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_PROG_RED:
            case KeyEvent.KEYCODE_Q:
                return 6;
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_E:
                return 7;
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_PROG_YELLOW:
                return 8;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BUTTON_START:
                return 9;
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_H:
            case KeyEvent.KEYCODE_BUTTON_Y:
                return 10;
            default:
                return 0;
        }
    }

    public boolean key(int code, boolean down, int repeat) {
        int a = action(code);
        if (a == 0) return false;
        if (state == S_PLAY) {
            switch (a) {
                case 1: gas = down; break;
                case 2: brk = down; break;
                case 3: left = down; break;
                case 4: right = down; break;
                case 5: if (down && repeat == 0) shiftGear(); break;
                case 6: if (down && repeat == 0) { blinkL = !blinkL; blinkR = false; haz = false; } break;
                case 7: if (down && repeat == 0) { blinkR = !blinkR; blinkL = false; haz = false; } break;
                case 8: if (down && repeat == 0) { haz = !haz; blinkL = blinkR = false; } break;
                case 9: if (down && repeat == 0) pauseGame(); break;
                case 10: if (down && repeat == 0) sfx.play(Sfx.HORN); break;
                default: break;
            }
            return true;
        }
        if (!down) return true;
        if (a >= 1 && a <= 5) sfx.play(Sfx.CLICK);
        String[] it = items();
        if (a == 1 || a == 3) {
            sel = (sel + it.length - 1) % it.length;
        } else if (a == 2 || a == 4) {
            sel = (sel + 1) % it.length;
        } else if (a == 5 && repeat == 0) {
            activate();
        } else if (a == 9 && repeat == 0 && state == S_PAUSE) {
            back();
        }
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (state != S_PLAY) {
            if (a == MotionEvent.ACTION_DOWN) {
                int i = (int) ((e.getY() - itemTop) / Math.max(1f, itemStep));
                String[] it = items();
                if (i >= 0 && i < it.length) {
                    sel = i;
                    activate();
                }
            }
            return true;
        }
        boolean g = false, b = false, l = false, r = false;
        if (a != MotionEvent.ACTION_UP && a != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (a == MotionEvent.ACTION_POINTER_UP && i == e.getActionIndex()) continue;
                float x = e.getX(i), y = e.getY(i);
                if (y > h * 0.7f) {
                    if (x < w * 0.5f) b = true; else g = true;
                } else if (x < w * 0.33f) l = true;
                else if (x > w * 0.67f) r = true;
            }
        }
        if ((a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN)) {
            int ix = e.getActionIndex();
            float x = e.getX(ix), y = e.getY(ix);
            if (y < h * 0.7f && x >= w * 0.33f && x <= w * 0.67f) shiftGear();
        }
        gas = g; brk = b; left = l; right = r;
        return true;
    }

    private void shiftGear() {
        if (Math.abs(speed) > 2f) {
            tip("Stop the car before changing gear", true, 2f);
            return;
        }
        gear = NEXT_GEAR[gear];
        sfx.play(Sfx.GEAR);
    }

    private String[] items() {
        if (state == S_MENU) {
            return new String[]{"Driving Test  (1 lap)", "Free Drive",
                    low ? "Graphics: LITE  (smooth on old TVs)" : "Graphics: BETTER",
                    sfx.muted ? "Sound: OFF" : "Sound: ON", "Exit"};
        } else if (state == S_PAUSE) {
            return new String[]{"Resume", "Restart", "Main Menu"};
        }
        return new String[]{"Play Again", "Main Menu"};
    }

    private void activate() {
        if (state == S_MENU) {
            switch (sel) {
                case 0: startGame(true); break;
                case 1: startGame(false); break;
                case 2: low = !low; break;
                case 3: sfx.setMuted(!sfx.muted); break;
                default:
                    if (getContext() instanceof Activity) ((Activity) getContext()).finish();
                    break;
            }
        } else if (state == S_PAUSE) {
            if (sel == 0) back();
            else if (sel == 1) startGame(test);
            else backToMenu();
        } else if (state == S_RESULT) {
            if (sel == 0) startGame(test); else backToMenu();
        }
    }

    // ---------------------------------------------------------------- game logic
    private void tip(String m, boolean bad, float secs) {
        msg = m;
        msgBad = bad;
        msgT = secs;
    }

    private void pen(String m, float pts) {
        pen(m, pts, false);
    }

    private void pen(String m, float pts, boolean crash) {
        sfx.play(crash ? Sfx.CRASH : Sfx.BEEP);
        score -= pts;
        tip(m, true, 2.6f);
        if (test && score <= 0f) {
            score = 0f;
            finish("TEST FAILED", false);
        }
    }

    private int phase(int k) {
        float t = (clock + k * 5f) % 16f;
        return t < 8f ? 0 : (t < 10f ? 1 : 2);
    }

    private void wrapPos() {
        while (pos < 0f) pos += TLEN;
        while (pos >= TLEN) pos -= TLEN;
    }

    private void spawnTraffic() {
        java.util.Random r = new java.util.Random(5);
        int placed = 0, tries = 0;
        while (placed < NC && tries < 3000) {
            tries++;
            float z = r.nextFloat() * TLEN;
            int lane = r.nextInt(3);
            float d = z > TLEN / 2 ? z - TLEN : z;
            if (Math.abs(d) < 9000f) continue;
            boolean ok = true;
            for (int j = 0; j < placed; j++) {
                if (cl[j] != lane) continue;
                float dz = Math.abs(cz[j] - z);
                dz = Math.min(dz, TLEN - dz);
                if (dz < 4500f) { ok = false; break; }
            }
            if (!ok) continue;
            cz[placed] = z;
            cl[placed] = lane;
            cs[placed] = LANEK[lane];
            cc[placed] = CARC[r.nextInt(CARC.length)];
            cb[placed] = false;
            placed++;
        }
        nc = placed;
    }

    private void updateTraffic(float dt) {
        for (int i = 0; i < nc; i++) {
            float target = LANEK[cl[i]];
            float front = cz[i] + CARL;
            for (int k = 0; k < SIGS.length; k++) {
                if (phase(k) != 2) continue;
                float d = SIGS[k] * (float) SEG - front;
                if (d < -TLEN / 2) d += TLEN;
                if (d > TLEN / 2) d -= TLEN;
                if (d > -150f && d < 4500f) target = Math.min(target, Math.max(0f, d - 300f) * 0.015f);
            }
            float g = 1e9f, sa = 0f;
            for (int j = 0; j < nc; j++) {
                if (j == i || cl[j] != cl[i]) continue;
                float dz = cz[j] - cz[i];
                if (dz < 0f) dz += TLEN;
                float gap = dz - CARL;
                if (gap < g) { g = gap; sa = cs[j]; }
            }
            if (Math.abs(LANEX[cl[i]] - playerX) < 0.4f) {
                float dz = (pos + NOSE - 1500f) - cz[i];
                if (dz < -TLEN / 2) dz += TLEN;
                if (dz > TLEN / 2) dz -= TLEN;
                float gap = dz - CARL;
                if (gap > -1200f && gap < g) { g = gap; sa = Math.max(0f, speed); }
            }
            if (g < 3000f) {
                target = Math.min(target, sa + Math.max(0f, g - 800f) * 0.012f);
                if (g < 400f) target = 0f;
            }
            cb[i] = cs[i] > target + 1f;
            if (cs[i] < target) cs[i] = Math.min(target, cs[i] + 12f * dt);
            else cs[i] = Math.max(target, cs[i] - 45f * dt);
            cz[i] += cs[i] * KU * dt;
            if (cz[i] >= TLEN) cz[i] -= TLEN;
        }
    }

    private void update(float dt) {
        if (state == S_PAUSE || state == S_RESULT) return;
        clock += dt;
        if (state == S_MENU) {
            pos += 25f * KU * dt;
            wrapPos();
            updateTraffic(dt);
            return;
        }
        time += dt;
        if (msgT > 0f) msgT -= dt;
        if (crashCd > 0f) crashCd -= dt;
        boolean engine = fuel > 0f;

        // steering wheel
        float st = (left ? -1f : 0f) + (right ? 1f : 0f);
        steer += (st - steer) * Math.min(1f, dt * 5f);

        // pedals
        if (gear == G_D) {
            if (gas && engine) speed += 14f * (1f - Math.max(0f, speed) / 125f) * dt;
        } else if (gear == G_R) {
            if (gas && engine) speed -= 6f * dt;
            if (speed < -15f) speed = -15f;
        }
        boolean driving = gas && engine && (gear == G_D || gear == G_R);
        if (!driving) {
            float drag = 1.2f + Math.abs(speed) * 0.025f;
            if (gear == G_P) drag += 25f;
            if (speed > 0f) speed = Math.max(0f, speed - drag * dt);
            else if (speed < 0f) speed = Math.min(0f, speed + drag * dt);
        }
        if (brk) {
            float b = 38f * dt;
            if (speed > 0f) speed = Math.max(0f, speed - b);
            else speed = Math.min(0f, speed + b);
        }
        if (speed > MAXK) speed = MAXK;

        // fuel
        if (engine) fuel -= dt * (0.0010f + 0.0018f * Math.abs(speed) / MAXK);
        if (fuel < 0f) fuel = 0f;
        if (!engine && Math.abs(speed) < 1f) {
            finish("OUT OF FUEL", false);
            return;
        }

        // movement
        float prevFront = pos + NOSE;
        float moved = speed * KU * dt;
        pos += moved;
        if (moved > 0f) traveled += moved;
        wrapPos();
        int ps = Math.min(T - 1, (int) (pos / SEG));
        float sp = Math.abs(speed) / MAXK;
        playerX += dt * 2f * sp * steer;
        playerX -= dt * 2f * sp * sp * CURVE[ps] * 0.28f;

        // walls and off-road
        float ax = Math.abs(playerX);
        if (ax > 1.02f) {
            offT += dt;
            if (speed > 20f) speed = Math.max(20f, speed - 40f * dt);
            if (offT > 2.5f) { offT = 0f; pen("Stay on the road", 3f); }
        } else {
            offT = 0f;
        }
        if (ax > 1.35f) {
            playerX = Math.signum(playerX) * 1.3f;
            if (crashCd <= 0f) {
                crashCd = 1.5f;
                speed *= 0.3f;
                pen("You hit the wall!", 10f, true);
            }
        }
        if (state != S_PLAY) return;

        // speed limit zone
        int zone = Math.min(LIM.length - 1, ps / 300);
        int lim = LIM[zone];
        if (zone != lastZone) {
            lastZone = zone;
            tip("Speed limit " + lim + " km/h", false, 2.5f);
        }
        if (speed > lim + 4f) {
            overT += dt;
            if (overT > 2.5f) { overT = 0f; pen("Over the speed limit (" + lim + ")", 3f); }
        } else {
            overT = 0f;
        }
        if (state != S_PLAY) return;

        // lane change + indicator discipline
        int ln = (int) Math.floor((playerX + 1f) * 1.5f);
        if (ln < 0) ln = 0;
        if (ln > 2) ln = 2;
        if (ln != lastLane) {
            if (ax < 1f && Math.abs(speed) > 5f) {
                boolean ok = (ln > lastLane && blinkR) || (ln < lastLane && blinkL) || haz;
                if (ok) { blinkL = blinkR = false; }
                else pen("Use your indicator when changing lanes", 2f);
            }
            lastLane = ln;
        }
        if (blinkL || blinkR || haz) {
            blinkT += dt;
            if (blinkT > 9f && !haz) { blinkL = blinkR = false; blinkT = 0f; }
        } else {
            blinkT = 0f;
        }
        if (state != S_PLAY) return;

        // traffic lights
        for (int k = 0; k < SIGS.length; k++) {
            float line = SIGS[k] * (float) SEG;
            float dprev = (line - prevFront) % TLEN;
            if (dprev < 0f) dprev += TLEN;
            if (moved > 0f && dprev > 0f && dprev <= moved && phase(k) == 2) {
                pen("Red light jumped!", 15f);
                break;
            }
        }
        if (state != S_PLAY) return;

        // traffic collisions
        for (int i = 0; i < nc; i++) {
            float rel = cz[i] - pos;
            if (rel > TLEN / 2) rel -= TLEN;
            else if (rel < -TLEN / 2) rel += TLEN;
            if (rel < NOSE && rel > NOSE - 1500f && crashCd <= 0f
                    && Math.abs(LANEX[cl[i]] - playerX) < 0.36f) {
                crashCd = 1.5f;
                if (rel > NOSE - 500f) {
                    pos = cz[i] - NOSE - 200f;
                    wrapPos();
                } else {
                    playerX += (playerX >= LANEX[cl[i]] ? 1f : -1f) * 0.15f;
                }
                speed *= 0.2f;
                pen("Crash! Keep a safe distance", 10f, true);
                break;
            }
        }
        if (state != S_PLAY) return;

        // petrol pump
        boolean nearPump = false;
        for (int p : PUMPS) if (Math.abs(ps - (p + 4)) <= 20) nearPump = true;
        if (nearPump && Math.abs(speed) < 8f && (gear == G_P || gear == G_N) && fuel < 1f) {
            fuel = Math.min(1f, fuel + 0.25f * dt);
            hint = "Refuelling...";
        } else {
            hint = "";
            if (gear == G_P) hint = "Press OK to shift into Drive (D)";
            else if (gear == G_D && Math.abs(speed) < 1f && time < 25f)
                hint = "Hold UP = accelerate     DOWN = brake     LEFT / RIGHT = steer";
            if (nearPump && fuel < 0.9f) hint = "Petrol pump: stop, press OK until P, wait to refuel";
            else if (fuel < 0.2f) hint = "Low fuel! Look for the petrol pump on the right";
            for (int k = 0; k < SIGS.length; k++) {
                float d = SIGS[k] * (float) SEG - (pos + NOSE);
                if (d < 0f) d += TLEN;
                if (d > 0f && d < 9000f && phase(k) == 2) { hint = "RED LIGHT - stop before the white line"; break; }
            }
        }

        // finish
        if (test && traveled >= TLEN) {
            boolean pass = score >= 70f;
            finish(pass ? "TEST PASSED" : "TEST FAILED", pass);
        }
    }

    // ---------------------------------------------------------------- drawing helpers
    private void fill(int c) {
        pf.setColor(c);
    }

    private void quad(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy) {
        path.rewind();
        path.moveTo(ax, ay);
        path.lineTo(bx, by);
        path.lineTo(cx, cy);
        path.lineTo(dx, dy);
        path.close();
        cv.drawPath(path, pf);
    }

    private void strip(int n, float off, float half, int color) {
        float xa1 = X[n] + W[n] * (off - half), xb1 = X[n] + W[n] * (off + half);
        float xa2 = X[n + 1] + W[n + 1] * (off - half), xb2 = X[n + 1] + W[n + 1] * (off + half);
        fill(color);
        quad(xa1, Y[n], xb1, Y[n], xb2, Y[n + 1], xa2, Y[n + 1]);
    }

    private void wall(int n, int side, float yb0, float yb1, int color) {
        float k = side * 1.5f;
        float xa = X[n] + W[n] * k, xb = X[n + 1] + W[n + 1] * k;
        float ya0 = Y[n] - SC[n] * yb0 * vs, ya1 = Y[n] - SC[n] * yb1 * vs;
        float yc0 = Y[n + 1] - SC[n + 1] * yb0 * vs, yc1 = Y[n + 1] - SC[n + 1] * yb1 * vs;
        fill(color);
        quad(xa, ya0, xa, ya1, xb, yc1, xb, yc0);
    }

    private void circle(float x, float y, float r, int color) {
        pa.setStyle(Paint.Style.FILL);
        pa.setColor(color);
        cv.drawCircle(x, y, r, pa);
    }

    private void ring(float x, float y, float r, int color, float wd) {
        pa.setStyle(Paint.Style.STROKE);
        pa.setStrokeWidth(wd);
        pa.setColor(color);
        cv.drawCircle(x, y, r, pa);
        pa.setStyle(Paint.Style.FILL);
    }

    private void line(float x1, float y1, float x2, float y2, int color, float wd) {
        pa.setStyle(Paint.Style.STROKE);
        pa.setStrokeWidth(wd);
        pa.setColor(color);
        cv.drawLine(x1, y1, x2, y2, pa);
        pa.setStyle(Paint.Style.FILL);
    }

    private void tri(float x1, float y1, float x2, float y2, float x3, float y3, int color) {
        pa.setStyle(Paint.Style.FILL);
        pa.setColor(color);
        path.rewind();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        cv.drawPath(path, pa);
    }

    private void pill(float l, float t, float r, float b, int color) {
        pa.setStyle(Paint.Style.FILL);
        pa.setColor(color);
        rf.set(l, t, r, b);
        float rad = (b - t) * 0.3f;
        cv.drawRoundRect(rf, rad, rad, pa);
    }

    private void txt(CharSequence s, float x, float y, float size, int col, Paint.Align al, boolean bold) {
        pa.setStyle(Paint.Style.FILL);
        pa.setTextSize(size);
        pa.setTextAlign(al);
        pa.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        pa.setColor(0xAA000000);
        cv.drawText(s, 0, s.length(), x + size * 0.05f, y + size * 0.05f, pa);
        pa.setColor(col);
        cv.drawText(s, 0, s.length(), x, y, pa);
    }

    private void fmt(float t) {
        int ms = (int) (t * 100f);
        int m = ms / 6000, s = (ms / 100) % 60, c = ms % 100;
        sb.setLength(0);
        sb.append(m).append(':');
        if (s < 10) sb.append('0');
        sb.append(s).append('.');
        if (c < 10) sb.append('0');
        sb.append(c);
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = Math.max(1, nw);
        h = Math.max(1, nh);
        vs = h * 0.5f;
        float hor = h * 0.42f;
        skyP.setShader(new LinearGradient(0, 0, 0, hor, 0xFF4F86C6, 0xFFDCE6EE, Shader.TileMode.CLAMP));
        hazeP.setShader(new LinearGradient(0, hor, 0, hor + h * 0.14f, 0xE6DCE6EE, 0x00DCE6EE, Shader.TileMode.CLAMP));
    }

    // ---------------------------------------------------------------- main draw
    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = (now - last) / 1e9f;
        last = now;
        if (dt > 0.05f) dt = 0.05f;
        if (dt < 0.001f) dt = 0.001f;
        update(dt);
        audio(dt);
        cv = c;
        drawScene();
        if (state != S_MENU) {
            drawCockpit();
            drawHud();
        }
        if (state == S_MENU) drawMenu("LITE DRIVING SCHOOL");
        else if (state == S_PAUSE) drawMenu("PAUSED");
        else if (state == S_RESULT) drawResult();
        if (low) postInvalidateDelayed(20); else postInvalidateOnAnimation();
    }

    private void drawScene() {
        final float hw = w * 0.5f, hor = h * 0.42f;
        cv.drawRect(0, 0, w, hor + 1f, skyP);
        fill(0xFF6E6A63);
        cv.drawRect(0, hor, w, h, pf);

        final int draw = low ? 64 : 100;
        float camZ = pos - CAMBACK;
        if (camZ < 0f) camZ += TLEN;
        int base = (int) (camZ / SEG);
        if (base >= T) base = T - 1;
        float frac = camZ - base * (float) SEG;
        float camX = playerX * RW;
        float x = 0f, dx = -(frac / SEG) * CURVE[base];
        for (int n = 0; n <= draw; n++) {
            int idx = (base + n) % T;
            float zc = n * (float) SEG - frac;
            if (zc < 150f) {
                SC[n] = -1f;
            } else {
                float sc = DEPTH / zc;
                SC[n] = sc;
                X[n] = hw + sc * (x - camX) * hw;
                W[n] = sc * RW * hw;
                Y[n] = hor + sc * CAMH * vs;
            }
            x += dx;
            dx += CURVE[idx];
        }

        // bucket traffic by segment
        for (int n = 0; n <= draw; n++) head[n] = -1;
        for (int i = 0; i < nc; i++) {
            float rel = cz[i] - camZ;
            if (rel < 0f) rel += TLEN;
            float q = rel + frac;
            int n = (int) (q / SEG);
            if (n >= NMIN && n < draw) {
                cn[i] = n;
                ct[i] = (q - n * (float) SEG) / SEG;
                nextC[i] = head[n];
                head[n] = i;
            }
        }

        for (int n = draw - 1; n >= NMIN; n--) {
            if (SC[n] <= 0f || SC[n + 1] <= 0f) continue;
            if (Y[n + 1] >= h) continue;
            int idx = (base + n) % T;

            // pavement
            fill(((idx >> 2) & 1) == 0 ? 0xFF77736B : 0xFF6E6A63);
            cv.drawRect(0, Y[n + 1], w, Y[n], pf);
            // asphalt
            strip(n, 0f, 1f, ((idx >> 3) & 1) == 0 ? 0xFF34343A : 0xFF37373D);
            // edge lines (yellow right-hand side divider, white kerb)
            strip(n, -0.97f, 0.025f, 0xFFE5C21A);
            strip(n, 0.97f, 0.025f, 0xFFF2F2F2);
            // lane dashes
            if ((idx & 3) < 2) {
                strip(n, -0.333f, 0.012f, 0xFFDDDDDD);
                strip(n, 0.333f, 0.012f, 0xFFDDDDDD);
            }
            int f = FEAT[idx];
            if (f >= 1 && f <= 6) {      // stop line
                float t = 0.3f;
                float xm = X[n] + (X[n + 1] - X[n]) * t, wm = W[n] + (W[n + 1] - W[n]) * t, ym = Y[n] + (Y[n + 1] - Y[n]) * t;
                fill(0xFFF2F2F2);
                quad(X[n] - W[n], Y[n], X[n] + W[n], Y[n], xm + wm, ym, xm - wm, ym);
            }

            // buildings
            int blk = idx / 16;
            for (int s = 0; s < 2; s++) {
                int side = s == 0 ? -1 : 1;
                int hs = hash(blk * 2 + s);
                if (((hs >>> 9) % 7) == 0) continue;           // gap between buildings
                float bh = 3000f + (hs & 0xFFF) * 1.3f;
                int col = PAL[((hs >>> 12) & 0xFF) % 6];
                if (side < 0) col = shade(col, 0.82f);
                wall(n, side, 0f, bh, col);
                if (!low && n < 50 && (idx % 3) == 1) {
                    int wc = shade(col, 0.42f);
                    for (float yb = 130f; yb + 190f < bh - 100f; yb += 380f) {
                        wall(n, side, yb, yb + 190f, wc);
                    }
                }
            }

            // signals and pumps
            if (f >= 1 && f <= 6) {
                int ph = phase(f - 1);
                for (int s = 0; s < 2; s++) {
                    float xp = X[n] + W[n] * 1.18f * (s == 0 ? -1f : 1f);
                    float ytop = Y[n] - SC[n] * 2600f * vs;
                    float pw = Math.max(1.5f, W[n] * 0.012f);
                    fill(0xFF333333);
                    cv.drawRect(xp - pw, ytop, xp + pw, Y[n], pf);
                    float bw = W[n] * 0.05f, bhh = SC[n] * 1000f * vs;
                    fill(0xFF1A1A1A);
                    cv.drawRect(xp - bw, ytop - bhh, xp + bw, ytop, pf);
                    float rr = Math.min(bw * 0.75f, bhh * 0.14f);
                    pf.setColor(ph == 2 ? 0xFFFF2A2A : 0xFF3A1010);
                    cv.drawCircle(xp, ytop - bhh * 0.8f, rr, pf);
                    pf.setColor(ph == 1 ? 0xFFFFC400 : 0xFF3A3010);
                    cv.drawCircle(xp, ytop - bhh * 0.5f, rr, pf);
                    pf.setColor(ph == 0 ? 0xFF2CFF5A : 0xFF103A18);
                    cv.drawCircle(xp, ytop - bhh * 0.2f, rr, pf);
                }
            } else if (f == 10) {
                float xp = X[n] + W[n] * 1.2f;
                float bw = W[n] * 0.05f;
                float ytop = Y[n] - SC[n] * 1500f * vs;
                fill(0xFFE53935);
                cv.drawRect(xp - bw, ytop, xp + bw, Y[n], pf);
                fill(0xFFFFFFFF);
                cv.drawRect(xp - bw, ytop, xp + bw, ytop + (Y[n] - ytop) * 0.25f, pf);
            }

            // traffic
            for (int i = head[n]; i >= 0; i = nextC[i]) {
                float t = ct[i];
                float xx = X[n] + (X[n + 1] - X[n]) * t;
                float ww = W[n] + (W[n + 1] - W[n]) * t;
                float yy = Y[n] + (Y[n + 1] - Y[n]) * t;
                drawCar(xx + ww * LANEX[cl[i]], yy, ww * 0.45f, cc[i], cb[i]);
            }
        }
        cv.drawRect(0, hor - 2f, w, hor + h * 0.14f, hazeP);
    }

    private void drawCar(float sx, float sy, float bw, int col, boolean braking) {
        if (bw < 1.5f) return;
        float bh = bw * 0.62f;
        fill(0x66000000);
        cv.drawRect(sx - bw * 0.55f, sy - bh * 0.05f, sx + bw * 0.55f, sy + bh * 0.05f, pf);
        fill(0xFF101010);
        cv.drawRect(sx - bw * 0.5f, sy - bh * 0.22f, sx - bw * 0.34f, sy, pf);
        cv.drawRect(sx + bw * 0.34f, sy - bh * 0.22f, sx + bw * 0.5f, sy, pf);
        fill(shade(col, 0.85f));
        cv.drawRect(sx - bw * 0.36f, sy - bh, sx + bw * 0.36f, sy - bh * 0.5f, pf);
        fill(0xFF1C2A38);
        cv.drawRect(sx - bw * 0.30f, sy - bh * 0.94f, sx + bw * 0.30f, sy - bh * 0.56f, pf);
        fill(col);
        cv.drawRect(sx - bw * 0.5f, sy - bh * 0.58f, sx + bw * 0.5f, sy - bh * 0.14f, pf);
        fill(braking ? 0xFFFF1F1F : 0xFF8E1A1A);
        cv.drawRect(sx - bw * 0.47f, sy - bh * 0.52f, sx - bw * 0.30f, sy - bh * 0.38f, pf);
        cv.drawRect(sx + bw * 0.30f, sy - bh * 0.52f, sx + bw * 0.47f, sy - bh * 0.38f, pf);
        fill(0xFF222222);
        cv.drawRect(sx - bw * 0.5f, sy - bh * 0.2f, sx + bw * 0.5f, sy - bh * 0.1f, pf);
    }

    // ---------------------------------------------------------------- cockpit
    private void drawCockpit() {
        // bonnet
        fill(0xFFD4531B);
        path.rewind();
        path.moveTo(w * 0.08f, h * 0.82f);
        path.quadTo(w * 0.5f, h * 0.60f, w * 0.92f, h * 0.82f);
        path.lineTo(w * 0.92f, h);
        path.lineTo(w * 0.08f, h);
        path.close();
        cv.drawPath(path, pf);
        pa.setStyle(Paint.Style.STROKE);
        pa.setStrokeWidth(h * 0.008f);
        pa.setColor(0xFFF0884A);
        path.rewind();
        path.moveTo(w * 0.1f, h * 0.815f);
        path.quadTo(w * 0.5f, h * 0.615f, w * 0.9f, h * 0.815f);
        cv.drawPath(path, pa);
        pa.setStyle(Paint.Style.FILL);

        // dashboard
        fill(0xFF14161A);
        cv.drawRect(0, h * 0.80f, w, h, pf);
        fill(0xFF2A2D33);
        cv.drawRect(0, h * 0.80f, w, h * 0.806f, pf);

        // speedometer
        float cx = w * 0.36f, cy = h * 0.89f, r = h * 0.115f;
        circle(cx, cy, r, 0xFF0B0C0E);
        ring(cx, cy, r, 0xFF5A5F66, h * 0.006f);
        for (int v = 0; v <= 160; v += 20) {
            float a = (float) Math.toRadians(135f + 270f * v / 160f);
            float co = (float) Math.cos(a), si = (float) Math.sin(a);
            boolean major = (v % 40) == 0;
            float r0 = r * (major ? 0.72f : 0.82f);
            line(cx + co * r0, cy + si * r0, cx + co * r * 0.92f, cy + si * r * 0.92f, 0xFFFFFFFF, h * 0.004f);
            if (major) txt(SPD_LBL[v / 40], cx + co * r * 0.55f, cy + si * r * 0.55f + h * 0.01f, h * 0.022f, 0xFFDDDDDD, Paint.Align.CENTER, false);
        }
        float sv = Math.min(160f, Math.abs(speed));
        float na = (float) Math.toRadians(135f + 270f * sv / 160f);
        line(cx, cy, cx + (float) Math.cos(na) * r * 0.82f, cy + (float) Math.sin(na) * r * 0.82f, 0xFFFF3B30, h * 0.007f);
        circle(cx, cy, r * 0.08f, 0xFFCCCCCC);
        int si2 = Math.round(Math.abs(speed));
        if (si2 != shownSpd) { shownSpd = si2; shownSpdS = Integer.toString(si2); }
        txt(shownSpdS, cx, cy + r * 0.52f, r * 0.30f, 0xFFFFFFFF, Paint.Align.CENTER, true);
        txt("km/h", cx, cy + r * 0.80f, r * 0.14f, 0xFFAAAAAA, Paint.Align.CENTER, false);

        // fuel gauge
        float fx = w * 0.63f, fr = h * 0.10f;
        circle(fx, cy, fr, 0xFF0B0C0E);
        ring(fx, cy, fr, fuel < 0.2f ? 0xFFFF3B30 : 0xFF5A5F66, h * 0.006f);
        txt("E", fx - fr * 0.55f, cy + fr * 0.55f, fr * 0.26f, 0xFFFF6B5A, Paint.Align.CENTER, true);
        txt("F", fx + fr * 0.55f, cy + fr * 0.55f, fr * 0.26f, 0xFF7CFF8A, Paint.Align.CENTER, true);
        txt("FUEL", fx, cy - fr * 0.35f, fr * 0.20f, 0xFFAAAAAA, Paint.Align.CENTER, false);
        float fa = (float) Math.toRadians(135f + 270f * fuel);
        line(fx, cy, fx + (float) Math.cos(fa) * fr * 0.8f, cy + (float) Math.sin(fa) * fr * 0.8f, 0xFFFF3B30, h * 0.006f);
        circle(fx, cy, fr * 0.08f, 0xFFCCCCCC);

        // indicators
        boolean blink = ((int) (clock * 2.5f) & 1) == 0;
        float iy = h * 0.865f;
        int lc = ((blinkL || haz) && blink) ? 0xFF35E65B : 0xFF1B3A22;
        int rc = ((blinkR || haz) && blink) ? 0xFF35E65B : 0xFF1B3A22;
        tri(w * 0.465f, iy, w * 0.485f, iy - h * 0.025f, w * 0.485f, iy + h * 0.025f, lc);
        tri(w * 0.535f, iy, w * 0.515f, iy - h * 0.025f, w * 0.515f, iy + h * 0.025f, rc);
        tri(w * 0.5f, iy - h * 0.035f, w * 0.488f, iy + h * 0.02f, w * 0.512f, iy + h * 0.02f,
                (haz && blink) ? 0xFFFF2A2A : 0xFF5A1414);

        // pedal indicators
        pill(w * 0.04f, h * 0.86f, w * 0.115f, h * 0.95f, brk ? 0xFFFF3B30 : 0xFF2A2D33);
        pill(w * 0.13f, h * 0.86f, w * 0.205f, h * 0.95f, gas ? 0xFF35E65B : 0xFF2A2D33);
        txt("BRAKE", w * 0.0775f, h * 0.915f, h * 0.026f, 0xFFFFFFFF, Paint.Align.CENTER, true);
        txt("GAS", w * 0.1675f, h * 0.915f, h * 0.026f, 0xFFFFFFFF, Paint.Align.CENTER, true);

        // steering wheel
        float wx = w * 0.87f, wy = h * 0.80f, wr = h * 0.20f;
        cv.save();
        cv.rotate(steer * 70f, wx, wy);
        line(wx - wr, wy, wx + wr, wy, 0xFF2B2E35, wr * 0.14f);
        line(wx, wy, wx, wy + wr, 0xFF2B2E35, wr * 0.14f);
        ring(wx, wy, wr, 0xFF23252B, wr * 0.17f);
        ring(wx, wy, wr * 1.07f, 0xFF3A3D45, wr * 0.025f);
        circle(wx, wy, wr * 0.27f, 0xFF30333B);
        line(wx, wy - wr * 1.0f, wx, wy - wr * 0.76f, 0xFFFFC107, wr * 0.07f);
        cv.restore();
    }

    // ---------------------------------------------------------------- HUD
    private void arrow(float cx, float cy, float s, int dir, int color) {
        pa.setStyle(Paint.Style.STROKE);
        pa.setStrokeWidth(s * 0.28f);
        pa.setStrokeCap(Paint.Cap.BUTT);
        pa.setColor(color);
        if (dir == 0) {
            cv.drawLine(cx, cy + s * 0.55f, cx, cy - s * 0.15f, pa);
            tri(cx - s * 0.4f, cy - s * 0.15f, cx + s * 0.4f, cy - s * 0.15f, cx, cy - s * 0.75f, color);
        } else {
            float vx = cx - dir * s * 0.4f;
            cv.drawLine(vx, cy + s * 0.55f, vx, cy - s * 0.1f, pa);
            pa.setStyle(Paint.Style.STROKE);
            pa.setStrokeWidth(s * 0.28f);
            pa.setColor(color);
            cv.drawLine(vx, cy - s * 0.1f, cx + dir * s * 0.1f, cy - s * 0.1f, pa);
            tri(cx + dir * s * 0.05f, cy - s * 0.1f - s * 0.4f, cx + dir * s * 0.05f, cy - s * 0.1f + s * 0.4f,
                    cx + dir * s * 0.6f, cy - s * 0.1f, color);
        }
        pa.setStyle(Paint.Style.FILL);
    }

    private void drawHud() {
        fmt(time);
        txt(sb, w * 0.05f, h * 0.085f, h * 0.075f, 0xFFFFFFFF, Paint.Align.LEFT, true);
        if (test) {
            sb.setLength(0);
            sb.append("Score ").append((int) score).append("   Lap ").append((int) (100f * Math.min(1f, traveled / TLEN))).append('%');
            txt(sb, w * 0.05f, h * 0.15f, h * 0.045f, score < 70f ? 0xFFFF8A80 : 0xFFB9F6CA, Paint.Align.LEFT, true);
        }

        // next-curve guidance
        int ps = Math.min(T - 1, (int) (pos / SEG));
        int dir = 0;
        float dist = 0f;
        for (int s = 0; s < 130; s++) {
            float cu = CURVE[(ps + s) % T];
            if (Math.abs(cu) > 0.8f) {
                dir = cu > 0f ? 1 : -1;
                dist = s * MPS;
                break;
            }
        }
        arrow(w * 0.5f, h * 0.17f, h * 0.10f, dir, dir == 0 ? 0xFF35E65B : 0xFFFF5A1F);
        sb.setLength(0);
        if (dir == 0) sb.append("Straight");
        else if (dist < 6f) sb.append(dir > 0 ? "Curve right" : "Curve left");
        else sb.append((int) (dist / 5f) * 5).append(" m");
        txt(sb, w * 0.5f, h * 0.31f, h * 0.06f, 0xFFFFFFFF, Paint.Align.CENTER, true);

        // speed limit sign
        int lim = LIM[Math.min(LIM.length - 1, ps / 300)];
        boolean over = speed > lim + 4f;
        float sx = w * 0.86f, sy = h * 0.14f, sr = h * 0.065f;
        circle(sx, sy, sr, (over && ((int) (clock * 3f) & 1) == 0) ? 0xFFFFD0D0 : 0xFFFFFFFF);
        ring(sx, sy, sr * 0.88f, 0xFFE53935, sr * 0.22f);
        sb.setLength(0);
        sb.append(lim);
        pa.setStyle(Paint.Style.FILL);
        pa.setTextSize(sr * 0.8f);
        pa.setTextAlign(Paint.Align.CENTER);
        pa.setTypeface(Typeface.DEFAULT_BOLD);
        pa.setColor(0xFF111111);
        cv.drawText(sb, 0, sb.length(), sx, sy + sr * 0.28f, pa);

        // gear selector
        for (int i = 0; i < 4; i++) {
            boolean on = i == gear;
            txt(GEARS[i], w * 0.955f, h * 0.10f + i * h * 0.085f, on ? h * 0.07f : h * 0.05f,
                    on ? 0xFFFFB300 : 0x99FFFFFF, Paint.Align.CENTER, true);
        }

        // messages
        if (msgT > 0f && msg.length() > 0) {
            float tw = h * 0.05f * msg.length() * 0.52f;
            pill(w * 0.5f - tw * 0.5f - h * 0.03f, h * 0.36f, w * 0.5f + tw * 0.5f + h * 0.03f, h * 0.435f,
                    msgBad ? 0xCCB71C1C : 0xCC1B5E20);
            txt(msg, w * 0.5f, h * 0.41f, h * 0.045f, 0xFFFFFFFF, Paint.Align.CENTER, true);
        } else if (hint.length() > 0) {
            float tw = h * 0.04f * hint.length() * 0.52f;
            pill(w * 0.5f - tw * 0.5f - h * 0.03f, h * 0.36f, w * 0.5f + tw * 0.5f + h * 0.03f, h * 0.43f, 0xAA000000);
            txt(hint, w * 0.5f, h * 0.405f, h * 0.04f, 0xFFFFEB3B, Paint.Align.CENTER, true);
        }
    }

    // ---------------------------------------------------------------- menus
    private void drawMenu(String title) {
        fill(0xAA000000);
        cv.drawRect(0, 0, w, h, pf);
        txt(title, w * 0.5f, h * 0.2f, h * 0.09f, 0xFFFFFFFF, Paint.Align.CENTER, true);
        String[] it = items();
        itemStep = h * 0.105f;
        itemTop = h * 0.36f - h * 0.07f;
        for (int i = 0; i < it.length; i++) {
            float y = h * 0.36f + i * itemStep;
            if (i == sel) pill(w * 0.25f, y - h * 0.07f, w * 0.75f, y + h * 0.025f, 0xFFFF9800);
            txt(it[i], w * 0.5f, y, h * 0.052f, i == sel ? 0xFF000000 : 0xFFFFFFFF, Paint.Align.CENTER, true);
        }
        if (state == S_MENU) {
            float s = h * 0.034f;
            txt("UP = accelerator (hold)     DOWN = brake (hold)     LEFT / RIGHT = steering", w * 0.5f, h * 0.855f, s, 0xFFDDDDDD, Paint.Align.CENTER, false);
            txt("OK = change gear when stopped (P > D > R > N)     BACK = pause", w * 0.5f, h * 0.905f, s, 0xFFDDDDDD, Paint.Align.CENTER, false);
            txt("1/RED = left     3/GREEN = right     2/YELLOW = hazard     4/BLUE = horn", w * 0.5f, h * 0.955f, s, 0xFFDDDDDD, Paint.Align.CENTER, false);
        }
    }

    private void drawResult() {
        fill(0xCC000000);
        cv.drawRect(0, 0, w, h, pf);
        txt(resTitle, w * 0.5f, h * 0.2f, h * 0.10f, resPass ? 0xFF69F0AE : 0xFFFF8A80, Paint.Align.CENTER, true);
        fmt(time);
        String t = sb.toString();
        if (test) {
            sb.setLength(0);
            sb.append("Score ").append((int) score).append(" / 100     Time ").append(t);
            txt(sb, w * 0.5f, h * 0.30f, h * 0.05f, 0xFFFFFFFF, Paint.Align.CENTER, true);
            txt(resPass ? "You may be given a licence! (need 70+)" : "You need 70+ points to pass. Try again!",
                    w * 0.5f, h * 0.36f, h * 0.04f, 0xFFDDDDDD, Paint.Align.CENTER, false);
        } else {
            txt("Time " + t, w * 0.5f, h * 0.30f, h * 0.05f, 0xFFFFFFFF, Paint.Align.CENTER, true);
        }
        String[] it = items();
        itemStep = h * 0.105f;
        itemTop = h * 0.55f - h * 0.07f;
        for (int i = 0; i < it.length; i++) {
            float y = h * 0.55f + i * itemStep;
            if (i == sel) pill(w * 0.3f, y - h * 0.07f, w * 0.7f, y + h * 0.025f, 0xFFFF9800);
            txt(it[i], w * 0.5f, y, h * 0.052f, i == sel ? 0xFF000000 : 0xFFFFFFFF, Paint.Align.CENTER, true);
        }
    }
}
