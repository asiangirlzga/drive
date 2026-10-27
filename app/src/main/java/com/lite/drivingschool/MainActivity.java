package com.lite.drivingschool;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

public class MainActivity extends Activity {

    private GameView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PadClient.attach(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new GameView(this);
        setContentView(view);
        view.requestFocus();
        hideBars();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.pauseGame();
        view.soundPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        view.soundRelease();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.resumeClock();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !view.back()) finish();
            return true;
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (view.key(code, down, e.getRepeatCount())) return true;
        return super.dispatchKeyEvent(e);
    }
}
