package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Filters Source and SET only; can open native power options on an explicit control-centre click.
 * No window content, text, account data, network access or direct shutdown command. */
public class SourceKeyService extends AccessibilityService {
    private static SourceKeyService connected;

    @Override protected void onServiceConnected() {
        connected = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }

    /** Opens the native confirmation/options only; never sends a sleep or shutdown command. */
    static boolean showPowerOptions() {
        return connected != null && connected.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (!RemoteControlsActivity.toggleIfVisible() && !MainActivity.toggleForegroundControls())
                    startActivity(new Intent(this, RemoteControlsActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_TV_INPUT) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (!MainActivity.toggleForegroundInputs()) {
                startActivity(new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_OPEN_INPUTS, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No accessibility event data is inspected or retained.
    }

    @Override
    public void onInterrupt() {
    }
}
