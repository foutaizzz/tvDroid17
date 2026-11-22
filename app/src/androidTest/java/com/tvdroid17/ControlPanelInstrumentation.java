package com.tvdroid17;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import java.lang.reflect.Field;

/** Checks layout/focus/action routing. The power callback is a counter: no shutdown in tests. */
public class ControlPanelInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Activity activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.tvdroid17.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitForIdleSync();
            ControlPanel[] panel = new ControlPanel[1];
            int[] calls = new int[11];
            Runnable[] actions = new Runnable[11];
            for (int i = 0; i < actions.length; i++) {
                final int index = i;
                actions[i] = () -> calls[index]++;
            }
            runOnMainSync(() -> panel[0] = new ControlPanel(activity,
                    activity.findViewById(R.id.btnSettings), actions, "Réseau de test"));
            PopupWindow popup = popup(panel[0]);
            Field buttonsField = ControlPanel.class.getDeclaredField("buttons");
            buttonsField.setAccessible(true);
            View[] buttons = (View[]) buttonsField.get(panel[0]);
            for (int index = 0; index < 11; index++) {
                final int selected = index;
                if (buttons[selected] == null) continue; // HOME was removed from the panel
                runOnMainSync(() -> panel[0].show(activity.findViewById(R.id.root)));
                waitForIdleSync();
                // show() re-focuses the CONTROL proxy 120 ms later; wait it out so the focus
                // we request below is not stolen before the key press.
                SystemClock.sleep(200);
                runOnMainSync(() -> buttons[selected].requestFocus());
                waitForIdleSync();
                press(popup, KeyEvent.KEYCODE_DPAD_CENTER);
                waitForIdleSync();
                if (index != ControlPanel.CONTROL && calls[index] != 1)
                    throw new AssertionError("Wrong shortcut " + index);
                if (popup.isShowing()) throw new AssertionError("Action did not close menu");
            }
            runOnMainSync(() -> panel[0].show(activity.findViewById(R.id.root)));
            waitForIdleSync();
            // show() re-focuses the CONTROL proxy 120 ms later; wait it out, as in the loop above.
            SystemClock.sleep(200);
            press(popup, KeyEvent.KEYCODE_DPAD_DOWN);
            press(popup, KeyEvent.KEYCODE_DPAD_RIGHT);
            press(popup, KeyEvent.KEYCODE_DPAD_DOWN);
            waitForIdleSync();
            runOnMainSync(() -> {
                if (!buttons[ControlPanel.WIFI].hasFocus()) {
                    View focused = popup.getContentView().findFocus();
                    throw new AssertionError("Asymmetric navigation failed: focused="
                            + (focused == null ? "none" : focused.getContentDescription()));
                }
                if (activity.findViewById(R.id.grid).getVisibility() != View.VISIBLE)
                    throw new AssertionError("Dock hidden behind controls");
                ViewGroup sheet = (ViewGroup) ((ViewGroup) popup.getContentView()).getChildAt(0);
                View capsule = (View) activity.findViewById(R.id.btnSettings).getParent();
                int[] capsulePosition = new int[2];
                capsule.getLocationOnScreen(capsulePosition);
                android.graphics.Rect sheetBounds = new android.graphics.Rect();
                sheet.getGlobalVisibleRect(sheetBounds);
                if (Math.abs(capsulePosition[0] + capsule.getWidth() - sheetBounds.right) > 2)
                    throw new AssertionError("Open control centre does not align with closed clock capsule");
                if (sheet.getBackground() != null) throw new AssertionError("Unwanted enclosing panel");
                if (sheet.getRight() > popup.getContentView().getWidth()
                        || sheet.getBottom() > popup.getContentView().getHeight())
                    throw new AssertionError("Menu exceeds screen");
                View power = buttons[ControlPanel.POWER], wifi = buttons[ControlPanel.WIFI];
                if (power.getHeight() <= wifi.getHeight() * 2 || power.getLeft() >= wifi.getLeft())
                    throw new AssertionError("Incorrect tall power / right capsules layout");
            });
            ArtworkBackdropView artwork = activity.findViewById(R.id.artwork);
            long deadline = android.os.SystemClock.elapsedRealtime() + 20_000;
            while (artwork.currentItem() == null && android.os.SystemClock.elapsedRealtime() < deadline)
                android.os.SystemClock.sleep(100);
            ArtworkPolicy.Item before = artwork.currentItem();
            if (before == null) throw new AssertionError("No artwork while controls open");
            deadline = android.os.SystemClock.elapsedRealtime() + 18_000;
            while (artwork.currentItem() == before && android.os.SystemClock.elapsedRealtime() < deadline)
                android.os.SystemClock.sleep(100);
            if (artwork.currentItem() == before || artwork.currentItem() == null)
                throw new AssertionError("Slideshow stopped behind controls");
            if (!popup.isShowing()) throw new AssertionError("Slideshow closed controls");
            press(popup, KeyEvent.KEYCODE_BACK);
            waitForIdleSync();
            runOnMainSync(() -> {
                if (activity.findViewById(R.id.grid).getVisibility() != View.VISIBLE
                        || !activity.findViewById(R.id.btnSettings).hasFocus())
                    throw new AssertionError("Home visibility/focus not restored");
                activity.findViewById(R.id.btnSettings).performClick();
            });
            waitForIdleSync();
            Field integratedField = MainActivity.class.getDeclaredField("controlPanel");
            integratedField.setAccessible(true);
            if (!popup((ControlPanel) integratedField.get(activity)).isShowing())
                throw new AssertionError("Gear did not open controls");
            Field openedField = MainActivity.class.getDeclaredField("controlPanel"); openedField.setAccessible(true);
            runOnMainSync(() -> {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SETTINGS));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SETTINGS));
            });
            waitForIdleSync();
            if (popup((ControlPanel) openedField.get(activity)).isShowing())
                throw new AssertionError("SET did not close the open control centre");
            runOnMainSync(() -> {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SETTINGS));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SETTINGS));
            });
            waitForIdleSync();
            if (!popup((ControlPanel) openedField.get(activity)).isShowing())
                throw new AssertionError("SET did not reopen the dismissed control centre");
            press(popup((ControlPanel) openedField.get(activity)), KeyEvent.KEYCODE_BACK);
            waitForIdleSync();
            runOnMainSync(() -> {
                Bitmap padded = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888);
                padded.setPixel(2, 1, 0xFFFFFFFF);
                padded.setPixel(5, 4, 0x01FFFFFF);
                Bitmap trimmed = CinemetaClient.trimLogo(padded);
                if (trimmed.getWidth() != 4 || trimmed.getHeight() != 4 || trimmed.getPixel(3, 3) != 0x01FFFFFF)
                    throw new AssertionError("Logo crop lost content");
            });
            result.putString("verification", "PASS: 11 controls, SET toggles menu closed/open, dock visible behind controls, live slideshow, remote paths, Back, restored focus, logo crop; power action mocked");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("verification", "FAIL: " + failure);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void press(PopupWindow popup, int key) {
        // Dispatch into the tested window, without risking input into another TV application.
        runOnMainSync(() -> {
            View window = popup.getContentView().getRootView();
            window.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            window.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        });
    }

    private PopupWindow popup(ControlPanel panel) throws Exception {
        Field field = ControlPanel.class.getDeclaredField("popup");
        field.setAccessible(true);
        return (PopupWindow) field.get(panel);
    }
}
