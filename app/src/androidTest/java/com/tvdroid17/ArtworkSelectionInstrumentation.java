package com.tvdroid17;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.lang.reflect.Field;

/** Real focus/lifecycle checks. Detail launch is intercepted so no profile or playback is modified. */
public class ArtworkSelectionInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Activity activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.tvdroid17.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            ArtworkBackdropView artwork = activity.findViewById(R.id.artwork);
            View target = activity.findViewById(R.id.artworkTarget);
            Field controller = MainActivity.class.getDeclaredField("artworkSlideshow");
            controller.setAccessible(true);
            ArtworkSlideshow slideshow = (ArtworkSlideshow) controller.get(activity);
            long deadline = SystemClock.elapsedRealtime() + 60_000;
            while (artwork.currentItem() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200);
            if (artwork.currentItem() == null) throw new AssertionError("No live artwork");
            waitForIdleSync();
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            waitForIdleSync();
            checkMain(() -> {
                if (!target.hasFocus()) throw new AssertionError("Up from dock skipped artwork");
                if (activity.findViewById(R.id.artworkHint).getVisibility() != View.VISIBLE)
                    throw new AssertionError("No selection hint");
            });
            checkMain(() -> {
                if (!"Regarder".contentEquals(((TextView) activity.findViewById(R.id.artworkHint)).getText()))
                    throw new AssertionError("Old selection caption remains");
            });
            SystemClock.sleep(300);
            checkMain(() -> {
                boolean zoomed = false;
                for (int i = 0; i < artwork.getChildCount(); i++) {
                    ImageView picture = (ImageView) ((ViewGroup) artwork.getChildAt(i)).getChildAt(0);
                    if (picture.getDrawable() != null && picture.getScaleX() > 1.03f) zoomed = true;
                }
                if (!zoomed) throw new AssertionError("Selected artwork did not zoom");
            });
            ArtworkPolicy.Item selected = artwork.currentItem();
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            awaitDifferent(artwork, selected.id);
            checkMain(() -> {
                int visible = 0;
                for (int i = 0; i < artwork.getChildCount(); i++) {
                    View layer = artwork.getChildAt(i);
                    if (layer.getVisibility() == View.VISIBLE) {
                        visible++;
                        if (layer.getAlpha() != 1f) throw new AssertionError("Selected title is still fading");
                    }
                }
                if (visible != 1) throw new AssertionError("Old selected artwork remains visible");
            });
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            awaitTitle(artwork, selected.id);
            selected = artwork.currentItem();
            final ArtworkPolicy.Item displayed = selected;
            checkMain(() -> {
                Handler main = (Handler) getField(slideshow, "main");
                Runnable next = (Runnable) getField(slideshow, "next");
                if (!main.hasCallbacks(next)) throw new AssertionError("Automatic rotation timer was not rescheduled after manual navigation");
                Intent details = PlayTorrioDetails.intent(displayed);
                if (!displayed.id.equals(details.getStringExtra("contentId"))
                        || !displayed.type.equals(details.getStringExtra("contentType"))
                        || !"detail".equals(details.getStringExtra("launchMode"))
                        || details.hasExtra("videoId") || details.hasExtra("playOnLoad"))
                    throw new AssertionError("Wrong title/type or playback requested");
                ArtworkPolicy.Item series = new ArtworkPolicy.Item("series", "tt10986410", "Ted Lasso", "", "");
                if (!"series".equals(PlayTorrioDetails.intent(series).getStringExtra("contentType")))
                    throw new AssertionError("Series treated as movie");
            });
            ActivityMonitor monitor = addMonitor("com.playtorrio.tv.MainActivity",
                    new ActivityResult(Activity.RESULT_CANCELED, null), true);
            checkMain(() -> { if (!target.hasFocus()) throw new AssertionError("Artwork lost focus before Enter: focus="
                    + activity.getCurrentFocus() + " controls=" + artwork.controlsVisible()
                    + " visibility=" + target.getVisibility() + " focusable=" + target.isFocusable()); });
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            waitForIdleSync();
            long launchDeadline = SystemClock.elapsedRealtime() + 1500;
            while (monitor.getHits() == 0 && SystemClock.elapsedRealtime() < launchDeadline) SystemClock.sleep(50);
            if (monitor.getHits() != 1) throw new AssertionError("Enter did not open PlayTorrio details");
            removeMonitor(monitor);
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            waitForIdleSync();
            checkMain(() -> {
                if (!activity.findViewById(R.id.btnSettings).hasFocus()) throw new AssertionError("Second Up did not reach gear");
            });
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            waitForIdleSync();
            checkMain(() -> { if (!target.hasFocus()) throw new AssertionError("Down from gear skipped artwork"); });
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            waitForIdleSync();
            // A playing trailer consumes the first Back (it only stops the trailer);
            // the next Back is the one that returns to the dock.
            boolean[] stillOnArtwork = {true};
            checkMain(() -> stillOnArtwork[0] = activity.getCurrentFocus() != null
                    && activity.getCurrentFocus().getId() == R.id.artworkTarget);
            if (stillOnArtwork[0]) {
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                waitForIdleSync();
            }
            checkMain(() -> {
                View focus = activity.getCurrentFocus();
                if (focus == null || focus.getId() != R.id.card) throw new AssertionError("Back did not restore dock focus");
            });
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            checkMain(() -> slideshow.setOnline(false));
            waitForIdleSync();
            checkMain(() -> {
                if (target.getVisibility() != View.GONE || artwork.currentItem() != null)
                    throw new AssertionError("Offline artwork remains clickable");
                Intent unsupported = PlayTorrioDetails.intent(new ArtworkPolicy.Item("movie", "bad/id", "", "", ""));
                if (unsupported != null) throw new AssertionError("Unsupported ID accepted");
            });
            verifyGlassAndCache(activity, artwork, slideshow);
            checkMain(() -> { callActivityOnPause(activity); callActivityOnResume(activity); });
            result.putString("verification", "PASS: Regarder, next/previous, auto-rotation timer resumes, detail intent, focus, offline cleanup, hardware-rendered glass follows artwork, idle blur has no polling, unchanged apps reused");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("verification", "FAIL: " + error);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void awaitDifferent(ArtworkBackdropView view, String oldId) {
        await(view, oldId, false);
    }

    private void awaitTitle(ArtworkBackdropView view, String id) { await(view, id, true); }

    private void await(ArtworkBackdropView view, String id, boolean equal) {
        long deadline = SystemClock.elapsedRealtime() + 35_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean[] done = {false};
            checkMain(() -> done[0] = view.currentItem() != null
                    && (id.equals(view.currentItem().id) == equal));
            if (done[0]) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Manual slider did not reach requested title");
    }

    private Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private Object getField(Object object, String name) {
        try { return field(object, name); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private void verifyGlassAndCache(Activity activity, ArtworkBackdropView artwork,
                                     ArtworkSlideshow slideshow) throws Exception {
        AppAdapter adapter = (AppAdapter) field(activity, "adapter");
        java.util.List<?> entries = (java.util.List<?>) field(adapter, "items");
        Object first = entries.get(0), second = entries.get(1);
        checkMain(() -> {
            if (adapter.reload() || entries.get(0) != first) throw new AssertionError("Unchanged app list reloaded");
            adapter.invalidatePackage(((AppAdapter.Entry) first).pkg);
            if (!adapter.reload() || entries.get(0) == first || entries.get(1) != second)
                throw new AssertionError("Package invalidation discarded unaffected icons");
            slideshow.pause();
        });
        Bitmap red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        red.eraseColor(0xFFFF0000);
        Bitmap blue = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        blue.eraseColor(0xFF0000FF);
        ArtworkPolicy.Item item = new ArtworkPolicy.Item("movie", "tt123", "Test", "", "");
        checkMain(() -> artwork.show(new CinemetaClient.Frame(item, red, null)));
        SystemClock.sleep(950);
        ControlPanel[] panel = new ControlPanel[1];
        checkMain(() -> {
            panel[0] = new ControlPanel(activity, activity.findViewById(R.id.btnSettings), new Runnable[11], "Test");
            panel[0].show(activity.findViewById(R.id.root));
            if (activity.findViewById(R.id.artworkHint).getVisibility() == View.VISIBLE
                    || activity.findViewById(R.id.artworkTarget).getVisibility() == View.VISIBLE)
                throw new AssertionError("Artwork selection remains active behind controls");
        });
        waitForIdleSync();
        FrostedControls glass = (FrostedControls) field(panel[0], "glass");
        androidx.recyclerview.widget.RecyclerView grid = activity.findViewById(R.id.grid);
        FrostedControls dockGlass = (FrostedControls) field(grid.getItemDecorationAt(0), "glass");
        Object dockBitmap = field(dockGlass, "backdrop");
        if (dockBitmap == null) throw new AssertionError("No live dock blur");
        Object bitmap = field(glass, "backdrop"), input = field(glass, "input");
        View[] buttons = (View[]) field(panel[0], "buttons");
        int[] spot = new int[2];
        checkMain(() -> buttons[0].getLocationOnScreen(spot));
        int redPixel = screenPixel(spot[0] + 24, spot[1] + 72);
        checkMain(() -> artwork.show(new CinemetaClient.Frame(item, blue, null)));
        checkMain(() -> {
            if (activity.findViewById(R.id.artworkTarget).getVisibility() == View.VISIBLE)
                throw new AssertionError("New metadata exposed the artwork target behind controls");
        });
        SystemClock.sleep(1_050);
        waitForIdleSync();
        int bluePixel = screenPixel(spot[0] + 24, spot[1] + 72);
        if (android.graphics.Color.red(redPixel) <= android.graphics.Color.blue(redPixel)
                || android.graphics.Color.blue(bluePixel) <= android.graphics.Color.red(bluePixel))
            throw new AssertionError("Old artwork remains in hardware-rendered glass");
        if (bitmap != field(glass, "backdrop") || input != field(glass, "input"))
            throw new AssertionError("Blur allocations rebuilt during transition");
        if (dockBitmap != field(dockGlass, "backdrop")) throw new AssertionError("Dock blur bitmap rebuilt during transition");
        Bitmap dockImage = (Bitmap) dockBitmap;
        int dockPixel = dockImage.getPixel(dockImage.getWidth() / 2, dockImage.getHeight() / 2);
        if (android.graphics.Color.blue(dockPixel) <= android.graphics.Color.red(dockPixel))
            throw new AssertionError("Old image remains in dock blur");
        int generation = ((Bitmap) bitmap).getGenerationId();
        SystemClock.sleep(350);
        if (generation != ((Bitmap) bitmap).getGenerationId()) throw new AssertionError("Idle menu still polls snapshots");
        checkMain(() -> {
            artwork.clear();
            try {
                if (field(glass, "backdrop") != null) throw new AssertionError("Offline clear retained old glass image");
                if (field(dockGlass, "backdrop") != null) throw new AssertionError("Offline clear retained dock blur");
            } catch (Exception failure) { throw new AssertionError(failure); }
            panel[0].dismiss();
            if (activity.findViewById(R.id.artworkTarget).getVisibility() != View.GONE
                    || activity.findViewById(R.id.artworkHint).getVisibility() == View.VISIBLE)
                throw new AssertionError("Dismiss restored stale artwork availability");
        });
    }

    private int screenPixel(int x, int y) {
        Bitmap screenshot = getUiAutomation().takeScreenshot();
        if (screenshot == null) throw new AssertionError("No screenshot for glass verification");
        int pixel = screenshot.getPixel(x, y);
        screenshot.recycle();
        return pixel;
    }

    private void checkMain(Runnable action) {
        Throwable[] error = new Throwable[1];
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
}
