package com.tvdroid17;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.reflect.Field;

/** Regressions for the user's logo flash, travelling focus pane and edge spacing. */
public class ContinuityInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Activity activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.tvdroid17.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            SystemClock.sleep(1000); waitForIdleSync();
            RecyclerView grid = activity.findViewById(R.id.grid);
            long layoutDeadline = SystemClock.elapsedRealtime() + 6000;
            boolean[] laidOut = {false};
            while (!laidOut[0] && SystemClock.elapsedRealtime() < layoutDeadline) {
                check(() -> laidOut[0] = grid.findViewHolderForAdapterPosition(0) != null);
                if (!laidOut[0]) SystemClock.sleep(50);
            }
            if (!laidOut[0]) throw new AssertionError("Dock did not become visible for continuity check");
            check(() -> grid.findViewHolderForAdapterPosition(0).itemView.findViewById(R.id.card).requestFocus());
            SystemClock.sleep(400);
            int oldHighlight = activity.getResources().getIdentifier("glass", "id", activity.getPackageName());
            View pane = activity.findViewById(oldHighlight);
            if (pane != null && pane.isShown() && pane.getAlpha() > .01f)
                throw new AssertionError("A separate transparent pane is still drawn over the selected tile");
            float density = activity.getResources().getDisplayMetrics().density;
            DockDecoration dock = (DockDecoration) grid.getItemDecorationAt(0);
            Field field = DockDecoration.class.getDeclaredField("rect"); field.setAccessible(true);
            RectF area = (RectF) field.get(dock);
            check(() -> {
                Rect bounds = new Rect(); activity.getCurrentFocus().getGlobalVisibleRect(bounds);
                int[] origin = new int[2]; grid.getLocationOnScreen(origin);
                float gap = (bounds.left - area.left - origin[0]) / density;
                if (gap < 11 || gap > 15) throw new AssertionError("Selected left card leaves " + gap + "dp at dock edge");
                TextView clock = activity.findViewById(R.id.clock);
                float scaled = activity.getResources().getDisplayMetrics().scaledDensity;
                if (Math.abs(clock.getTextSize() / scaled - 13) > .1f) throw new AssertionError("Home clock should be 13sp");
            });
            View first = activity.getCurrentFocus();
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            for (int n = 0; n < 14; n++) {
                check(() -> {
                    View selected = activity.getCurrentFocus();
                    if (Math.abs(first.getTranslationX()) > .1 || Math.abs(selected.getTranslationX()) > .1)
                        throw new AssertionError("Tile travels laterally instead of scaling in place");
                    if (selected.getScaleX() < .99f || selected.getScaleX() > 1.221f)
                        throw new AssertionError("Selected tile scale overshoots");
                });
                SystemClock.sleep(16);
            }
            ArtworkBackdropView artwork = activity.findViewById(R.id.artwork);
            Field controller = MainActivity.class.getDeclaredField("artworkSlideshow"); controller.setAccessible(true);
            ArtworkSlideshow slideshow = (ArtworkSlideshow) controller.get(activity);
            ArtworkPolicy.Item item = new ArtworkPolicy.Item("movie", "tt123", "Temporary title", "", "", "", "");
            Bitmap picture = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            Bitmap logo = Bitmap.createBitmap(80, 20, Bitmap.Config.ARGB_8888); logo.eraseColor(0xFFFFFFFF);
            CinemetaClient.Frame pending = CinemetaClient.Frame.class
                    .getDeclaredConstructor(ArtworkPolicy.Item.class, Bitmap.class, Bitmap.class, boolean.class)
                    .newInstance(item, picture, null, true);
            check(() -> { slideshow.pause(); artwork.show(pending); });
            SystemClock.sleep(250);
            check(() -> {
                if (visibleTitle(artwork, item.title)) throw new AssertionError("Text title flashes while the real logo is pending");
                artwork.updateLogo(item, logo);
                if (visibleTitle(artwork, item.title)) throw new AssertionError("Text remains beside loaded logo");
                artwork.show(pending);
                artwork.updateLogo(item, null);
                if (!visibleTitle(artwork, item.title)) throw new AssertionError("Missing-logo fallback never appears");
                artwork.show(pending);
                artwork.resolvePendingTitle();
                if (!visibleTitle(artwork, item.title)) throw new AssertionError("Failed navigation strands first title invisible");
                artwork.show(pending);
                slideshow.setHeld(true);
                try {
                    Field request = ArtworkSlideshow.class.getDeclaredField("requestedPosition");
                    request.setAccessible(true); request.setInt(slideshow, 1);
                } catch (Exception failure) { throw new AssertionError(failure); }
                slideshow.setHeld(false);
                if (!visibleTitle(artwork, item.title)) throw new AssertionError("Cancelled navigation strands first title invisible");
                slideshow.resume(true);
            });
            result.putString("verification", "PASS: no travelling pane, 11-15dp selected edge gap, 13sp clock, local bounded scale, no pending-logo text flash, missing-logo fallback");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("verification", "FAIL: " + error);
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private boolean visibleTitle(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int n = 0; n < ((ViewGroup) view).getChildCount(); n++)
            if (visibleTitle(((ViewGroup) view).getChildAt(n), text)) return true;
        return false;
    }
    private void check(Runnable action) {
        Throwable[] error = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
}
