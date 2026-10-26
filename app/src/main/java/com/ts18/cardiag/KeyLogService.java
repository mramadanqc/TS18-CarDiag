package com.ts18.cardiag;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Logs key events and foreground window changes into the black box (diagnostics only). */
public class KeyLogService extends AccessibilityService {
    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        BlackBox.add(this, "KEY " + KeyEvent.keyCodeToString(e.getKeyCode())
                + (e.getAction() == KeyEvent.ACTION_DOWN ? " down" : " up") + " src=" + e.getSource());
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent ev) {
        if (ev != null && ev.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            BlackBox.add(this, "WINDOW " + ev.getPackageName() + " / " + ev.getClassName());
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        BlackBox.add(this, "KeyLogService connected");
    }
}
