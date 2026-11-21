package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/** Temporarily lowers MediaTek TV backlight while the screensaver is visible. */
final class ScreensaverBacklight {
    private static final String SETTING = "picture_backlight";
    private static final int SCREENSAVER_BACKLIGHT = 1; // MediaTek picture_backlight uses 0–100.
    private static final String PREFS = "screensaver_backlight";
    private static final String RESTORE_VALUE = "restore_value";

    private ScreensaverBacklight() {}

    static void dim(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;

        SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Recover a previous interrupted dream before starting another one.
        if (state.contains(RESTORE_VALUE)) {
            if (!restore(context)) return;
        }

        int previous;
        try {
            previous = Settings.Global.getInt(context.getContentResolver(), SETTING);
        } catch (Exception unavailable) {
            return;
        }

        // Persist before changing the TV setting so a process restart can restore it.
        if (!state.edit().putInt(RESTORE_VALUE, previous).commit()) return;
        try {
            if (!Settings.Global.putInt(context.getContentResolver(), SETTING, SCREENSAVER_BACKLIGHT)) {
                state.edit().remove(RESTORE_VALUE).commit();
            }
        } catch (RuntimeException unavailable) {
            state.edit().remove(RESTORE_VALUE).commit();
        }
    }

    static boolean restore(Context context) {
        SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!state.contains(RESTORE_VALUE)) return true;
        int previous = state.getInt(RESTORE_VALUE, -1);
        if (previous < 0) {
            state.edit().remove(RESTORE_VALUE).commit();
            return false;
        }
        try {
            if (Settings.Global.putInt(context.getContentResolver(), SETTING, previous)) {
                state.edit().remove(RESTORE_VALUE).commit();
                return true;
            }
        } catch (RuntimeException unavailable) {
            // Keep the saved value; MainActivity will retry when it returns to the foreground.
        }
        return false;
    }
}
