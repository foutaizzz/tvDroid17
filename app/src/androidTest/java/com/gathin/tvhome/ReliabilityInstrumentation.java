package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/** Extended recovery checks: failed replacements, actual app lifecycle and repeated interruptions. */
public class ReliabilityInstrumentation extends Instrumentation {
    private Activity activity;
    private ArtworkBackdropView artwork;
    private ArtworkSlideshow slideshow;
    private int checked;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.gathin.tvhome.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            artwork = activity.findViewById(R.id.artwork);
            slideshow = (ArtworkSlideshow) field(activity, "artworkSlideshow");
            awaitReady("initial load");
            @SuppressWarnings("unchecked") List<ArtworkPolicy.Item> original = new ArrayList<>((List<ArtworkPolicy.Item>) field(slideshow, "catalogue"));
            ArtworkPolicy.Item visible = artwork.currentItem();
            Bitmap picture = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            picture.eraseColor(0xFF28624A);
            List<ArtworkPolicy.Item> unavailable = new ArrayList<>();
            unavailable.add(new ArtworkPolicy.Item("movie", "tt123", "Unavailable test image", "https://invalid.example/blocked.jpg", ""));
            unavailable.add(original.get(0));
            check(() -> {
                slideshow.pause();
                artwork.show(new CinemetaClient.Frame(visible, picture, null));
                set(slideshow, "resumed", true); set(slideshow, "online", true); set(slideshow, "held", false);
                set(slideshow, "catalogue", unavailable);
                set(slideshow, "catalogueTime", SystemClock.elapsedRealtime());
                set(slideshow, "displayedPosition", 0);
                invokeLoad(0, false);
            });
            SystemClock.sleep(350); waitForIdleSync();
            check(() -> {
                if (artwork.currentItem() != visible) throw new AssertionError("One failed automatic download erased a valid backdrop and title");
                if ((int) field(slideshow, "position") != 1) throw new AssertionError("Failed image was not skipped");
            });
            int failedGeneration = (int) field(slideshow, "generation");
            long retryDeadline = SystemClock.elapsedRealtime() + 3000;
            while ((int) field(slideshow, "generation") == failedGeneration && SystemClock.elapsedRealtime() < retryDeadline)
                SystemClock.sleep(40);
            if ((int) field(slideshow, "generation") == failedGeneration) throw new AssertionError("Recovery still waits the full slideshow interval");
            awaitReady("automatic retry to next valid image");
            check(() -> {
                if ((int) field(slideshow, "consecutiveFailures") != 0) throw new AssertionError("Automatic retry did not succeed");
                slideshow.pause();
                set(slideshow, "position", 0);
                slideshow.resume(true);
            });
            awaitReady("startup skips broken first image");
            check(() -> {
                set(slideshow, "catalogue", original);
                set(slideshow, "catalogueTime", SystemClock.elapsedRealtime());
            });

            check(() -> {
                set(slideshow, "catalogue", unavailable);
                set(slideshow, "catalogueTime", SystemClock.elapsedRealtime());
                set(slideshow, "displayedPosition", 0);
                slideshow.setHeld(true);
                invokeLoad(0, true);
            });
            SystemClock.sleep(350);
            check(() -> {
                if ((int) field(slideshow, "failedPosition") != 0)
                    throw new AssertionError("Manual failure did not retain its position for next/previous navigation");
                slideshow.move(1);
            });
            awaitReady("manual navigation recovers after unavailable artwork");
            check(() -> {
                if (!original.get(0).id.equals(artwork.currentItem().id))
                    throw new AssertionError("Manual next did not skip unavailable image");
                set(slideshow, "catalogue", original);
                set(slideshow, "catalogueTime", SystemClock.elapsedRealtime());
                slideshow.setHeld(false);
            });

            for (int cycle = 0; cycle < 8; cycle++) {
                // These settings pages make the launcher really pause/resume, without changing settings.
                getTargetContext().startActivity(new Intent(cycle % 2 == 0 ? Settings.ACTION_SOUND_SETTINGS : Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                awaitResumed(false);
                check(() -> { if (artwork.currentItem() != null) throw new AssertionError("Paused launcher retained artwork"); });
                SystemClock.sleep(cycle % 3 == 0 ? 80 : 300);
                getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN)
                        .setClassName(getTargetContext(), "com.gathin.tvhome.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                awaitResumed(true);
                awaitReady("app return " + cycle);
                if (cycle % 2 == 0) {
                    check(() -> slideshow.setHeld(true));
                    for (int key = 0; key < 9; key++) {
                        final int direction = key % 3 == 0 ? -1 : 1;
                        check(() -> slideshow.move(direction));
                        SystemClock.sleep(35);
                    }
                    awaitReady("rapid navigation " + cycle);
                    check(() -> slideshow.setHeld(false));
                }
                android.util.Log.i("TvHomeReliability", "Completed real app return " + (cycle + 1));
            }
            for (int cycle = 0; cycle < 3; cycle++) {
                check(() -> slideshow.setOnline(false));
                check(() -> { if (artwork.currentItem() != null) throw new AssertionError("Offline artwork retained"); });
                check(() -> slideshow.setOnline(true));
                awaitReady("network recovery " + cycle);
            }
            for (int cycle = 0; cycle < 8; cycle++) {
                final ControlPanel[] panel = new ControlPanel[1];
                check(() -> { panel[0] = new ControlPanel(activity, activity.findViewById(R.id.btnSettings), new Runnable[11], "Test"); panel[0].show(activity.findViewById(R.id.root)); });
                SystemClock.sleep(60);
                check(() -> panel[0].dismiss());
                final SettingsMenu[] menu = new SettingsMenu[1];
                check(() -> { menu[0] = new SettingsMenu(activity, activity.findViewById(R.id.btnSettings), new Runnable[8]); menu[0].show(activity.findViewById(R.id.root)); });
                SystemClock.sleep(60);
                check(() -> menu[0].dismiss());
            }
            awaitReady("after repeated menus");
            result.putString("verification", "PASS: failed manual/automatic replacements recover and skip broken artwork; 8 actual app returns, 36 rapid slider commands, 3 network recoveries, 16 menu openings; " + checked + " settled image/title checks");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("verification", "FAIL: " + failure + " state=" + state());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void awaitReady(String stage) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 35_000;
        boolean[] ready = {false};
        while (SystemClock.elapsedRealtime() < deadline) {
            check(() -> {
                Future<?> pending = (Future<?>) field(slideshow, "pending");
                ready[0] = artwork.currentItem() != null && (int) field(slideshow, "requestedPosition") == -1
                        && !(boolean) field(artwork, "logoPending") && (pending == null || pending.isDone());
            });
            if (ready[0]) break;
            SystemClock.sleep(100);
        }
        if (!ready[0]) throw new AssertionError(stage + " did not recover: " + state());
        SystemClock.sleep(750);
        check(() -> {
            int front = (int) field(artwork, "front");
            Object[] layers = (Object[]) field(artwork, "layers");
            View layer = (View) layers[front];
            ImageView picture = (ImageView) field(layer, "picture"), logo = (ImageView) field(layer, "logo");
            TextView title = (TextView) field(layer, "title");
            if (layer.getVisibility() != View.VISIBLE || layer.getAlpha() < .99f || picture.getDrawable() == null)
                throw new AssertionError(stage + " has no visible picture: " + state());
            if (!(logo.getVisibility() == View.VISIBLE && logo.getDrawable() != null)
                    && !(title.getVisibility() == View.VISIBLE && title.length() > 0))
                throw new AssertionError(stage + " has neither logo nor text: " + state());
            if (artwork.controlsVisible()) throw new AssertionError(stage + " left controls state active");
        });
        checked++;
    }
    private void awaitResumed(boolean value) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while ((boolean) field(activity, "homeResumed") != value && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40);
        if ((boolean) field(activity, "homeResumed") != value) throw new AssertionError("Real lifecycle did not reach resumed=" + value);
    }
    private String state() {
        if (slideshow == null) return "not started";
        return "generation=" + field(slideshow, "generation") + ", displayed=" + field(slideshow, "displayedPosition")
                + ", requested=" + field(slideshow, "requestedPosition") + ", held=" + field(slideshow, "held")
                + ", resumed=" + field(slideshow, "resumed") + ", online=" + field(slideshow, "online")
                + ", front=" + field(artwork, "front") + ", logoPending=" + field(artwork, "logoPending");
    }
    private void invokeLoad(int slot, boolean manual) {
        try { Method method = ArtworkSlideshow.class.getDeclaredMethod("loadSlot", int.class, boolean.class); method.setAccessible(true); method.invoke(slideshow, slot, manual); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private void set(Object object, String name, Object value) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private void check(Runnable action) {
        Throwable[] error = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
}
