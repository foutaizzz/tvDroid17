package com.tvdroid17;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** One frosted dock sampled from artwork only, so the dock never captures itself. */
public class DockDecoration extends RecyclerView.ItemDecoration {
    private final int dockSize;
    private final float density;
    private final float horizontalInset, verticalInset, radius;
    private final ArtworkBackdropView artwork;
    private final FrostedControls glass = new FrostedControls();
    private final RectF rect = new RectF();
    private RecyclerView grid;
    private Drawable material;

    DockDecoration(int dockSize, float density, ArtworkBackdropView artwork) {
        this.dockSize = dockSize;
        this.density = density;
        this.artwork = artwork;
        horizontalInset = artwork.getResources().getDimension(R.dimen.dock_inset);
        verticalInset = artwork.getResources().getDimension(R.dimen.dock_vertical_inset);
        radius = artwork.getResources().getDimension(R.dimen.dock_radius);
        artwork.setDockVisualChangedListener(this::onArtworkChanged);
    }

    /** Drops the shared blurred backdrop when Home is hidden; it is recaptured on the next frame. */
    void releaseBackdrop() {
        glass.clear();
        if (grid != null) grid.invalidate();
    }

    Drawable materialFor(View owner, float cornerRadiusDp) {
        return glass.material(owner, cornerRadiusDp * density, false);
    }

    /** The shared frosted-glass surface, so the control panel reuses the dock's live blur. */
    FrostedControls sharedGlass() { return glass; }

    Drawable controlButtonMaterial(View owner, float cornerRadiusDp) {
        return glass.material(owner, cornerRadiusDp * density,
                FrostedControls.CONTROL_BUTTON_TINT, false);
    }

    private void onArtworkChanged() {
        if (artwork.currentItem() == null) {
            glass.clear();
            if (grid != null) grid.invalidate();
            return;
        }
        glass.captureArtwork(artwork);
        // Explicit redraw: relying only on the drawable callback missed repaints at boot/Home.
        if (grid != null) grid.invalidate();
    }

    /** Recaptures the dock blur when the artwork is showing but the glass was cleared (memory trim). */
    void refreshIfNeeded() {
        if (artwork.currentItem() != null && !glass.hasBackdrop()) onArtworkChanged();
    }

    @Override public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                         @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
                                 @NonNull RecyclerView.State state) {
        if (material == null) {
            grid = parent;
            material = glass.material(parent, radius, false);
            material.setCallback(parent);
        }
        // Safety net: if a capture was missed (e.g. backdrop released while Home was hidden and
        // no artwork event fired since), recapture now instead of staying flat until navigation.
        refreshIfNeeded();
        if (artwork.currentItem() != null && !glass.hasBackdrop() && grid != null)
            grid.post(this::refreshIfNeeded); // capture could not run yet (not laid out): retry next frame
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float left = child.getLeft() + card.getLeft();
            float top = child.getTop() + card.getTop() + child.getTranslationY();
            if (!any) { rect.set(left, top, left + card.getWidth(), top + card.getHeight()); any = true; }
            else rect.union(left, top, left + card.getWidth(), top + card.getHeight());
        }
        if (!any) return;
        rect.inset(-horizontalInset, -verticalInset);
        material.setBounds(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right), Math.round(rect.bottom));
        material.draw(canvas);
    }
}
