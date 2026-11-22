package com.tvdroid17;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.lang.reflect.Field;

/**
 * Diagnostic only (no app change): dumps the dock glass internal state after boot, after an UP
 * key (focus on artwork card -> setArtworkSelected) and after DOWN keys (back to dock, then into
 * the app grid), to see when the dock backdrop is null/blank vs present.
 */
public class DockBlurDiagInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Activity activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.tvdroid17.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            ArtworkBackdropView artwork = activity.findViewById(R.id.artwork);
            long deadline = SystemClock.elapsedRealtime() + 60_000;
            while (artwork.currentItem() == null && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(200);
            if (artwork.currentItem() == null) throw new AssertionError("No live artwork");
            waitForIdleSync();
            SystemClock.sleep(3_000); // let the first crossfade finish
            String boot = state(activity, artwork);
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);   // dock -> artwork card (setArtworkSelected)
            waitForIdleSync();
            SystemClock.sleep(1_200);
            String afterUp = state(activity, artwork);
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); // card -> dock
            waitForIdleSync();
            SystemClock.sleep(800);
            String afterDown1 = state(activity, artwork);
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); // dock -> app grid (scroll -> onDraw)
            waitForIdleSync();
            SystemClock.sleep(1_200);
            String afterDown2 = state(activity, artwork);
            result.putString("verification", "PASS boot=[" + boot + "] afterUP=[" + afterUp
                    + "] afterDOWN1=[" + afterDown1 + "] afterDOWN2=[" + afterDown2 + "]");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("verification", "FAIL: " + failure);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private String state(Activity activity, ArtworkBackdropView artwork) {
        try {
            Object dock = field(activity, "dockDecoration");
            Object glass = field(dock, "glass");
            Bitmap backdrop = (Bitmap) field(glass, "backdrop");
            long capturedFrame = (long) field(glass, "capturedFrame");
            float blend = (float) field(glass, "blend");
            return "item=" + (artwork.currentItem() != null)
                    + " backdrop=" + describe(backdrop)
                    + " capturedFrame=" + capturedFrame + "/" + artwork.frameRevision()
                    + " blend=" + blend + " snapshotBlend=" + artwork.snapshotBlend();
        } catch (Exception e) {
            return "error:" + e;
        }
    }

    private String describe(Bitmap b) {
        if (b == null) return "null";
        double sum = 0; int n = 0;
        for (int y = 0; y < b.getHeight(); y += 4)
            for (int x = 0; x < b.getWidth(); x += 4) {
                int c = b.getPixel(x, y);
                sum += ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF) + ((c >>> 24));
                n++;
            }
        double mean = sum / (4.0 * n);
        return mean < 8 ? "BLANK(mean=" + (int) mean + ")" : "set(mean=" + (int) mean + ")";
    }

    private Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }
}
