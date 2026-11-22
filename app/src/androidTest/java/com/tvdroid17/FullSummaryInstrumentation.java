package com.tvdroid17;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;

/** Long summaries must be fully laid out, readable by remote, and stay above the dock. */
public class FullSummaryInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Activity activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.tvdroid17.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            Field field = MainActivity.class.getDeclaredField("artworkSlideshow"); field.setAccessible(true);
            ArtworkSlideshow slideshow = (ArtworkSlideshow) field.get(activity);
            ArtworkBackdropView artwork = activity.findViewById(R.id.artwork);
            String summary = "Un voyage commence dans une ville inconnue. Ses habitants cachent un secret et les héros doivent comprendre leur histoire pour retrouver leur famille. ".repeat(18) + "FIN DU RÉSUMÉ.";
            Bitmap picture = Bitmap.createBitmap(16, 16, Bitmap.Config.RGB_565);
            picture.eraseColor(0xFF354057);
            main(() -> {
                slideshow.pause();
                artwork.show(new CinemetaClient.Frame(new ArtworkPolicy.Item("movie", "tt123", "Test résumé", "", "", "Aventure", summary), picture, null));
                activity.findViewById(R.id.artworkTarget).requestFocus();
            });
            SystemClock.sleep(450); waitForIdleSync();
            TextView description = activity.findViewById(R.id.artworkDescription);
            main(() -> {
                if (description.getEllipsize() != null || description.getMaxLines() < 100)
                    throw new AssertionError("Summary is still truncated to " + description.getMaxLines() + " lines");
                int lines = description.getLayout().getLineCount();
                if (description.getLayout().getLineEnd(lines - 1) != summary.length())
                    throw new AssertionError("Last words missing from summary layout");
                View watch = activity.findViewById(R.id.artworkHint);
                int[] xy = new int[2]; watch.getLocationOnScreen(xy);
                if (xy[1] + watch.getHeight() > artwork.getHeight() * .72f)
                    throw new AssertionError("Long summary pushes Watch into dock");
            });
            for (int n = 0; n < 80; n++) {
                main(() -> activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)));
                SystemClock.sleep(20);
                if (!activity.findViewById(R.id.artworkTarget).hasFocus()) break;
            }
            View scroll = activity.findViewById(R.id.summaryScroll);
            if(scroll.getScrollY()<=0 || scroll.canScrollVertically(1))
                throw new AssertionError("Remote did not reach final words of full summary");
            // The whole text must be scrollable, with an ordinary Down leaving after the last page.
            if (activity.findViewById(R.id.artworkTarget).hasFocus())
                throw new AssertionError("Remote cannot leave summary at its end");
            main(() -> activity.findViewById(R.id.artworkTarget).requestFocus());
            main(() -> activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)));
            if (!activity.findViewById(R.id.btnSettings).hasFocus())
                throw new AssertionError("Up at beginning does not reach controls");
            result.putString("verification", "PASS: full long summary laid out, remote scroll reaches end, Watch stays above dock, focus exits both ends");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void main(Runnable action) {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
}
