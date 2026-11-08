package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.PopupWindow;
import java.lang.reflect.Field;

/** Exercises SET and preferences without changing a system setting or invoking power. */
public class SettingsEntryInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Activity activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.gathin.tvhome.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            runOnMainSync(() -> activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SETTINGS)));
            SystemClock.sleep(250);
            Object controls = field(activity, "controlPanel");
            if (controls == null || !((PopupWindow) field(controls, "popup")).isShowing())
                throw new AssertionError("SET does not open home control centre");
            View[] buttons = (View[]) field(controls, "buttons");
            if (!buttons[ControlPanel.SETTINGS].getContentDescription().toString().contains("Préférences"))
                throw new AssertionError("Old Settings shortcut remains");
            ActivityMonitor monitor = addMonitor(DeviceSettingsActivity.class.getName(), new ActivityResult(Activity.RESULT_OK,null), true);
            runOnMainSync(() -> buttons[ControlPanel.SETTINGS].performClick());
            SystemClock.sleep(350);
            if (monitor.getHits()!=1)
                throw new AssertionError("No device preferences entrance");
            removeMonitor(monitor);
            result.putString("verification", "PASS: SET opens shared controls; preferences shortcut opens device preferences entrance; no power or settings values changed");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
}
