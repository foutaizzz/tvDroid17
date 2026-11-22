package com.tvdroid17;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.view.View;
import android.view.KeyEvent;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONObject;
import java.lang.reflect.Field;

/** Visual bounds checks; power actions are never invoked. */
public class PolishInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Activity activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.tvdroid17.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            ControlPanel[] panel = new ControlPanel[1];
            runOnMainSync(() -> {
                panel[0] = new ControlPanel(activity, activity.findViewById(R.id.btnSettings), new Runnable[11], "Test");
                panel[0].show(activity.findViewById(R.id.root));
            });
            waitForIdleSync();
            Field field = ControlPanel.class.getDeclaredField("buttons"); field.setAccessible(true);
            View active = ((View[]) field.get(panel[0]))[ControlPanel.CONTROL];
            float top = active.getTop() - active.getHeight() * (active.getScaleY() - 1) / 2;
            if (top < 1) throw new AssertionError("White circle extends above header bounds: " + top);
            runOnMainSync(() -> panel[0].dismiss());
            ArtworkBackdropView artwork = activity.findViewById(R.id.artwork);
            Field controller = MainActivity.class.getDeclaredField("artworkSlideshow"); controller.setAccessible(true);
            ArtworkSlideshow slideshow = (ArtworkSlideshow) controller.get(activity);
            Field interval = ArtworkSlideshow.class.getDeclaredField("INTERVAL_MS"); interval.setAccessible(true);
            if (interval.getLong(null) != 10_000) throw new AssertionError("Wrong slideshow interval");
            long deadline = SystemClock.elapsedRealtime() + 25_000;
            while (artwork.currentItem() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            if (artwork.currentItem() == null) throw new AssertionError("No first backdrop");
            if (new CatalogueStore(getTargetContext()).read().isEmpty()) throw new AssertionError("Metadata cache unavailable for next start");
            ArtworkPolicy.Item item = CinemetaClient.parseItem("movie", new JSONObject()
                    .put("id", "tt123").put("name", "Titre de test")
                    .put("genres", new org.json.JSONArray("[\"Adventure\",\"Comedy\",\"Mystery\"]"))
                    .put("description", "Un résumé suffisamment long pour vérifier la disposition verticale sous le titre, avant le bouton Regarder, sans recouvrir les favoris."));
            if (!"Aventure · Comédie · Mystère".equals(item.genres)) throw new AssertionError("Genres not localized");
            Bitmap picture = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            picture.eraseColor(0xFF354057);
            Field generationField = ArtworkSlideshow.class.getDeclaredField("generation"); generationField.setAccessible(true);
            int[] selectionGeneration = {0};
            // onResume() refocuses a grid tile once the package list is applied (MainActivity
            // focusTile in the reload callback); wait for that initial focus to settle so it
            // cannot steal the focus we request below.
            RecyclerView grid = activity.findViewById(R.id.grid);
            long gridDeadline = SystemClock.elapsedRealtime() + 20_000;
            while ((grid.getAdapter() == null || grid.getAdapter().getItemCount() == 0
                    || grid.getChildCount() == 0) && SystemClock.elapsedRealtime() < gridDeadline)
                SystemClock.sleep(100);
            if (grid.getAdapter() == null || grid.getAdapter().getItemCount() == 0)
                throw new AssertionError("App grid never populated");
            waitForIdleSync();
            SystemClock.sleep(500);
            checkMain(() -> {
                slideshow.pause();
                artwork.show(new CinemetaClient.Frame(item, picture, null));
                try { selectionGeneration[0] = generationField.getInt(slideshow); }
                catch (Exception failure) { throw new AssertionError(failure); }
                activity.findViewById(R.id.artworkTarget).requestFocus();
            });
            if (generationField.getInt(slideshow) != selectionGeneration[0])
                throw new AssertionError("Selection invalidated the displayed title's pending logo");
            // requestFocus() is asynchronous: wait until the artwork target really holds focus.
            View targetEarly = activity.findViewById(R.id.artworkTarget);
            long focusDeadline = SystemClock.elapsedRealtime() + 5_000;
            while (!targetEarly.hasFocus() && SystemClock.elapsedRealtime() < focusDeadline)
                SystemClock.sleep(50);
            if (!targetEarly.hasFocus())
                throw new AssertionError("artworkTarget never gained focus; currentFocus=" + activity.getCurrentFocus());
            waitForIdleSync();
            checkMain(() -> {
                View details = activity.findViewById(R.id.artworkDetails);
                View target = activity.findViewById(R.id.artworkTarget);
                TextView genres = activity.findViewById(R.id.artworkGenres);
                TextView description = activity.findViewById(R.id.artworkDescription);
                View summary = activity.findViewById(R.id.summaryScroll);
                View watch = activity.findViewById(R.id.artworkHint);
                float density = activity.getResources().getDisplayMetrics().density;
                TextView watchText = (TextView) watch;
                float contentWidth = watchText.getPaint().measureText(watchText.getText().toString())
                        + watchText.getCompoundDrawablesRelative()[0].getIntrinsicWidth() + watchText.getCompoundDrawablePadding();
                if (watch.getHeight() != Math.round(34 * density)
                        || Math.abs(watch.getPaddingStart() / density - 32) > .5f
                        || Math.abs(watch.getPaddingEnd() / density - 32) > .5f
                        || Math.abs(watch.getWidth() - contentWidth - 64 * density) > 2)
                    throw new AssertionError("Watch pill does not preserve balanced 32dp side spaces");
                if (!details.isShown() || !genres.getText().toString().contains("Aventure") || description.length() == 0)
                    throw new AssertionError("Missing selected metadata: detailsShown=" + details.isShown()
                            + " detailsVis=" + details.getVisibility() + " targetVis=" + target.getVisibility()
                            + " targetFocusable=" + target.isFocusable() + " targetFocused=" + target.hasFocus()
                            + " currentFocus=" + activity.getCurrentFocus()
                            + " genres='" + genres.getText() + "' descLen=" + description.length());
                if (target.getTop() <= artwork.titleBottom() || summary.getTop() < genres.getBottom()
                        || watch.getTop() < summary.getBottom()) throw new AssertionError("Metadata not stacked after title");
                if (Math.abs((target.getTop() - artwork.titleBottom()) / density - 14) > .5f
                        || Math.abs((summary.getTop() - genres.getBottom()) / density - 16) > .5f
                        || Math.abs((watch.getTop() - summary.getBottom()) / density - 20) > .5f)
                    throw new AssertionError("Vertical gaps differ from measured reference");
                if (target.getBottom() > 700) throw new AssertionError("Details overlap dock: " + target.getBottom());
            });
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            if (grid.getWidth() != activity.findViewById(R.id.root).getWidth())
                throw new AssertionError("Alpha layer can clip the expanded dock corners");
            checkMain(() -> grid.findViewHolderForAdapterPosition(0).itemView.findViewById(R.id.card).requestFocus());
            SystemClock.sleep(250);
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            for (int sample = 0; sample < 14; sample++) {
                checkMain(() -> {
                    View card = activity.getCurrentFocus();
                    if (Math.abs(card.getTranslationX()) > .1 || card.getScaleX() > 1.221f)
                        throw new AssertionError("Tile focus travels or overshoots");
                });
                SystemClock.sleep(16);
            }
            checkMain(() -> {
                View card = activity.getCurrentFocus();
                if (Math.abs(card.getScaleX() - 1.22f) > .001f)
                    throw new AssertionError("Selected icon did not settle at reference size");
            });
            DockDecoration dock = (DockDecoration) grid.getItemDecorationAt(0);
            Field dockArea = DockDecoration.class.getDeclaredField("rect"); dockArea.setAccessible(true);
            android.graphics.RectF area = (android.graphics.RectF) dockArea.get(dock);
            float density = activity.getResources().getDisplayMetrics().density;
            float widthRatio = area.width() / activity.findViewById(R.id.root).getWidth();
            int[] gridPosition = new int[2]; grid.getLocationOnScreen(gridPosition);
            float dockTop = (gridPosition[1] + area.top) / density;
            if (widthRatio < .96f || widthRatio > .97f || Math.abs(dockTop - 392) > 2)
                throw new AssertionError("Dock differs from measured reference: width=" + widthRatio + " top=" + dockTop);
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            SystemClock.sleep(450); waitForIdleSync();
            checkMain(() -> {
                View firstRow = grid.getLayoutManager().findViewByPosition(0);
                // With few installed apps Android clamps at the end of real content.
                if (firstRow == null || firstRow.getTop() >= grid.getPaddingTop()
                        || grid.getPaddingTop() - firstRow.getTop() > 2 * activity.getResources().getDimensionPixelSize(R.dimen.tile_row_height))
                    throw new AssertionError("App grid did not reveal the next row progressively: "
                            + (firstRow == null ? "missing" : firstRow.getTop() / density));
            });
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            SystemClock.sleep(220);
            SystemClock.sleep(250); waitForIdleSync();
            checkMain(() -> { callActivityOnPause(activity); callActivityOnResume(activity); });
            result.putString("verification", "PASS: balanced watch pill, vertical gaps, full header circle, live metadata, local bounded focus, app-grid lift and sharp favorites return");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("verification", "FAIL: " + failure);
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void checkMain(Runnable action) {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
}
