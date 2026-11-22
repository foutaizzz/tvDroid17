package com.tvdroid17;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Filters Source, SET and volume keys globally; can open native power options on an explicit control-centre click.
 * No window content, text, account data, network access or direct shutdown command. */
public class SourceKeyService extends AccessibilityService {
    private static SourceKeyService connected;
    private static final long VOLUME_REPEAT_START_MS = 450L;
    private static final long VOLUME_REPEAT_INTERVAL_MS = 110L;
    private static final long VOLUME_RELEASE_GRACE_MS = 220L;
    private final Handler volumeHandler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private int repeatingVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
    private Runnable volumeRepeat;
    private Runnable volumeRelease;

    @Override protected void onServiceConnected() {
        connected = this;
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    @Override public void onDestroy() {
        stopVolumeRepeat();
        if (connected == this) connected = null;
        super.onDestroy();
    }

    /** Opens the native confirmation/options only; never sends a sleep or shutdown command. */
    static boolean showPowerOptions() {
        return connected != null && connected.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return handleVolumeKey(event);
        }
        if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (!RemoteControlsActivity.toggleIfVisible() && !MainActivity.toggleForegroundControls())
                    startActivity(new Intent(this, RemoteControlsActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
            return true;
        }
        if (keyCode != KeyEvent.KEYCODE_TV_INPUT) return false;
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

    /** Replaces the firmware's one-step-only behavior while this global key filter is active. */
    private boolean handleVolumeKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (volumeRelease != null) {
                volumeHandler.removeCallbacks(volumeRelease);
                volumeRelease = null;
            }
            if (event.getRepeatCount() == 0 || repeatingVolumeKey != keyCode) {
                stopVolumeRepeat();
                repeatingVolumeKey = keyCode;
                adjustVolume(keyCode);
                volumeRepeat = new Runnable() {
                    @Override public void run() {
                        if (repeatingVolumeKey != keyCode) return;
                        adjustVolume(keyCode);
                        volumeHandler.postDelayed(this, VOLUME_REPEAT_INTERVAL_MS);
                    }
                };
                volumeHandler.postDelayed(volumeRepeat, VOLUME_REPEAT_START_MS);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (repeatingVolumeKey == keyCode) {
                long heldMs = event.getEventTime() - event.getDownTime();
                if (heldMs >= VOLUME_RELEASE_GRACE_MS) {
                    stopVolumeRepeat();
                } else {
                    // Some TV remotes emit short down/up pulses while the physical key is held.
                    // Wait briefly for the next pulse before treating this as a real release.
                    if (volumeRelease != null) volumeHandler.removeCallbacks(volumeRelease);
                    volumeRelease = this::stopVolumeRepeat;
                    volumeHandler.postDelayed(volumeRelease, VOLUME_RELEASE_GRACE_MS);
                }
            }
            return true;
        }
        return true;
    }

    private void adjustVolume(int keyCode) {
        if (audioManager == null) return;
        int direction = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        audioManager.adjustVolume(direction, AudioManager.FLAG_SHOW_UI);
    }

    private void stopVolumeRepeat() {
        if (volumeRepeat != null) volumeHandler.removeCallbacks(volumeRepeat);
        if (volumeRelease != null) volumeHandler.removeCallbacks(volumeRelease);
        volumeRepeat = null;
        volumeRelease = null;
        repeatingVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No accessibility event data is inspected or retained.
    }

    @Override
    public void onInterrupt() {
        stopVolumeRepeat();
    }
}
