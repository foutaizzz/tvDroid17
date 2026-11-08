package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

/** First usable backdrop after creating HOME; no power action or system reboot. */
public class StartupArtworkInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            long start = SystemClock.elapsedRealtime();
            Activity activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.gathin.tvhome.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ArtworkBackdropView artwork = activity.findViewById(R.id.artwork);
            boolean[] ready = {false};
            while (!ready[0] && SystemClock.elapsedRealtime() - start < 30_000) {
                runOnMainSync(() -> ready[0] = artwork.currentItem() != null);
                if (!ready[0]) SystemClock.sleep(25);
            }
            if (!ready[0]) throw new AssertionError("No first backdrop within 30s");
            result.putLong("first_backdrop_ms", SystemClock.elapsedRealtime() - start);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("verification", "FAIL: " + failure);
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
