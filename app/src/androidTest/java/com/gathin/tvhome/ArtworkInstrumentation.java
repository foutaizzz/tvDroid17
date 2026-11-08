package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.lang.reflect.Field;

/** Real-device lifecycle checks; no production test switches, fake image loaders or disk fixtures. */
public class ArtworkInstrumentation extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            // Surface transport/decoder failures before the controller's intentional quiet fallback.
            CinemetaClient probe = new CinemetaClient();
            probe.load(probe.catalogue().get(0));
            Activity activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.gathin.tvhome.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ArtworkBackdropView view = activity.findViewById(R.id.artwork);
            Field field = MainActivity.class.getDeclaredField("artworkSlideshow");
            field.setAccessible(true);
            ArtworkSlideshow slideshow = (ArtworkSlideshow) field.get(activity);
            awaitArtwork(view);
            runOnMainSync(() -> slideshow.setOnline(false));
            assertEmpty(view, "Offline must remove every displayed bitmap");
            runOnMainSync(() -> slideshow.setOnline(true));
            awaitArtwork(view);
            runOnMainSync(() -> callActivityOnPause(activity));
            assertEmpty(view, "Leaving Home must release displayed artwork");
            runOnMainSync(() -> callActivityOnResume(activity));
            runOnMainSync(() -> slideshow.setOnline(false));
            SystemClock.sleep(2_000);
            assertEmpty(view, "A late network result must not restore artwork while offline");
            runOnMainSync(() -> slideshow.setOnline(true));
            awaitArtwork(view);
            result.putString("verification", "PASS: online artwork, offline cleanup, pause cleanup, stale callback rejection, reconnect");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("verification", "FAIL: " + failure);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void awaitArtwork(ArtworkBackdropView view) {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean[] loaded = {false};
            runOnMainSync(() -> loaded[0] = hasBitmap(view));
            if (loaded[0]) return;
            SystemClock.sleep(200);
        }
        throw new AssertionError("No live Cinemeta artwork appeared within 20 seconds");
    }

    private void assertEmpty(ArtworkBackdropView view, String message) {
        boolean[] loaded = {true};
        runOnMainSync(() -> loaded[0] = hasBitmap(view));
        if (loaded[0]) throw new AssertionError(message);
    }

    private boolean hasBitmap(ArtworkBackdropView view) {
        for (int i = 0; i < view.getChildCount(); i++) {
            ViewGroup layer = (ViewGroup) view.getChildAt(i);
            ImageView image = (ImageView) layer.getChildAt(0);
            if (image.getDrawable() != null) return true;
        }
        return false;
    }
}
