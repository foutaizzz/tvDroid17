package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.PopupWindow;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.reflect.Field;

/** Verifies alignment and all settings routes with counters, without changing TV settings. */
public class SettingsMenuInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Activity activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.gathin.tvhome.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            SystemClock.sleep(1200); waitForIdleSync();
            View anchor = activity.findViewById(R.id.btnSettings);
            RecyclerView grid = activity.findViewById(R.id.grid);
            Object dock = grid.getItemDecorationAt(0);
            Field rect = DockDecoration.class.getDeclaredField("rect"); rect.setAccessible(true);
            RectF bounds = (RectF) rect.get(dock);
            check(() -> {
                View capsule = (View) anchor.getParent();
                int[] capsulePosition = new int[2], gridPosition = new int[2];
                capsule.getLocationOnScreen(capsulePosition); grid.getLocationOnScreen(gridPosition);
                float difference = capsulePosition[0] + capsule.getWidth() - gridPosition[0] - bounds.right;
                if (Math.abs(difference) > 2) throw new AssertionError("Clock capsule does not align with dock: " + difference + "px");
            });
            Class<?> type = Class.forName("com.gathin.tvhome.SettingsMenu");
            int[] called = new int[8]; Runnable[] actions = new Runnable[8];
            for (int n = 0; n < actions.length; n++) { final int index = n; actions[n] = () -> called[index]++; }
            Object menu = type.getDeclaredConstructor(Activity.class, View.class, Runnable[].class)
                    .newInstance(activity, anchor, actions);
            Field rowField = type.getDeclaredField("rows"); rowField.setAccessible(true);
            View[] rows = (View[]) rowField.get(menu);
            Field popupField = type.getDeclaredField("popup"); popupField.setAccessible(true);
            PopupWindow popup = (PopupWindow) popupField.get(menu);
            for (int n = 0; n < rows.length; n++) {
                final int index = n;
                check(() -> invoke(menu, "show", activity.findViewById(R.id.root)));
                SystemClock.sleep(250); waitForIdleSync();
                check(() -> {
                    if (!popup.isShowing() || !((ArtworkBackdropView) activity.findViewById(R.id.artwork)).controlsVisible())
                        throw new AssertionError("Settings screen not modal");
                    float density = activity.getResources().getDisplayMetrics().density;
                    if (Math.abs(rows[index].getWidth() / density - 390) > 1
                            || Math.abs(rows[index].getHeight() / density - 34) > 1
                            || ((android.graphics.drawable.GradientDrawable) rows[index].getBackground()).getCornerRadius() / density < 16)
                        throw new AssertionError("Settings rows do not match two-column reference layout");
                    if (index == 0 && !rows[0].hasFocus()) throw new AssertionError("Initial settings row has no selection");
                    if (index == 0) {
                        ((ArtworkBackdropView) activity.findViewById(R.id.artwork)).clear();
                        try {
                            for (Field menuField : type.getDeclaredFields())
                                if (menuField.getType() == FrostedControls.class || menuField.getType() == android.graphics.Bitmap.class)
                                    throw new AssertionError("Pastel settings background must not retain movie artwork");
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    }
                    rows[index].requestFocus(); rows[index].performClick();
                });
                SystemClock.sleep(100);
                if (popup.isShowing() || called[index] != 1) throw new AssertionError("Wrong settings action " + index);
            }
            check(() -> { invoke(menu, "show", activity.findViewById(R.id.root)); invoke(menu, "dismiss"); });
            if (!anchor.hasFocus() || ((ArtworkBackdropView) activity.findViewById(R.id.artwork)).controlsVisible())
                throw new AssertionError("Settings dismissal did not restore home");
            result.putString("verification", "PASS: capsule aligned to dock, two-column full-screen settings, eight mocked routes, modal focus and clean dismissal");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("verification", "FAIL: " + failure); finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void invoke(Object target, String name, View... args) {
        try {
            if (args.length == 0) target.getClass().getDeclaredMethod(name).invoke(target);
            else target.getClass().getDeclaredMethod(name, View.class).invoke(target, args[0]);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private void check(Runnable action) {
        Throwable[] error = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
}
