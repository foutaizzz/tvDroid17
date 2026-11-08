package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Two transient image layers crossfade as a unit, including the top-left logo/title. */
public class ArtworkBackdropView extends FrameLayout {
    private final Layer[] layers = new Layer[2];
    private int front = -1;
    private int transition;
    private ArtworkPolicy.Item item;
    private Runnable changed;
    private Runnable visualChanged;
    private Runnable dockVisualChanged;
    private boolean selected;
    private boolean logoPending;
    private boolean controlsVisible;
    private boolean descriptionPending;
    private Bitmap testBackground;
    private long frameRevision, contentRevision;

    public ArtworkBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (int i = 0; i < layers.length; i++) {
            layers[i] = new Layer(context);
            addView(layers[i], new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    void show(CinemetaClient.Frame artwork) {
        show(artwork, false);
    }

    void show(CinemetaClient.Frame artwork, boolean descriptionPending) {
        show(artwork, descriptionPending, false);
    }

    /**
     * @param immediate manual navigation shows the loaded title directly, without a crossfade;
     *                  the automatic carousel keeps its 600 ms fade.
     */
    void show(CinemetaClient.Frame artwork, boolean descriptionPending, boolean immediate) {
        int version = ++transition;
        Layer old = front < 0 ? null : layers[front];
        int next = front == 0 ? 1 : 0;
        Layer incoming = layers[next];
        incoming.clear();
        incoming.picture.setImageBitmap(testBackground == null ? artwork.background : testBackground);
        incoming.picture.setScaleX(selected ? 1.035f : 1f);
        incoming.picture.setScaleY(selected ? 1.035f : 1f);
        incoming.detailsShade.setAlpha(selected ? 1f : 0f);
        if (testBackground != null) {
            incoming.logo.setVisibility(GONE);
            incoming.title.setVisibility(GONE);
        } else if (artwork.logo != null) {
            incoming.logo.setImageBitmap(artwork.logo);
            incoming.logo.setVisibility(VISIBLE);
            incoming.title.setVisibility(GONE);
            sizeLogo(incoming.logo, artwork.logo);
        } else {
            incoming.logo.setVisibility(GONE);
            incoming.title.setText(artwork.title);
            incoming.title.setVisibility(artwork.logoPending ? INVISIBLE : VISIBLE);
        }
        if (old != null) {
            old.animate().cancel();
            old.setAlpha(1f);
        }
        incoming.bringToFront();
        incoming.setVisibility(VISIBLE);
        if (immediate) {
            // Manual navigation: swap instantly, no fade, old layer released at once.
            incoming.animate().cancel();
            incoming.setAlpha(1f);
            if (old != null) old.clear();
        } else {
            incoming.setAlpha(0f);
            // Keep the old layer under the new one during the automatic crossfade.
            incoming.animate().alpha(1f).withLayer().setDuration(old == null ? 180 : 600)
                    .setInterpolator(new DecelerateInterpolator(1.3f))
                    .setUpdateListener(animation -> notifyVisualChangedThrottled())
                    .withEndAction(() -> {
                        if (transition == version && old != null) old.clear();
                        notifyVisualChanged();
                    }).start();
        }
        front = next;
        item = artwork.item;
        this.descriptionPending = descriptionPending;
        frameRevision++;
        contentRevision++;
        logoPending = artwork.logoPending;
        if (changed != null) changed.run();
        notifyVisualChanged();
    }

    ArtworkPolicy.Item currentItem() { return item; }

    void setTestBackground(Bitmap bitmap) {
        if (!BuildConfig.DEBUG) return;
        testBackground = bitmap;
        if (front >= 0) {
            Layer current = layers[front];
            current.picture.setImageBitmap(bitmap);
            current.logo.setVisibility(GONE);
            current.title.setVisibility(GONE);
            contentRevision++;
            notifyVisualChanged();
        }
    }

    void updateDescription(ArtworkPolicy.Item expected, String description) {
        if (item != expected || description == null) return;
        if (!description.trim().isEmpty()) item.description = description;
        descriptionPending = false;
        contentRevision++;
        if (changed != null) changed.run();
    }

    boolean isDescriptionPending(ArtworkPolicy.Item expected) {
        return item == expected && descriptionPending;
    }

    long frameRevision() { return frameRevision; }
    long contentRevision() { return contentRevision; }
    View snapshotSource() { return front < 0 ? this : layers[front]; }
    View previousSnapshotSource() {
        if (front < 0) return null;
        Layer previous = layers[1 - front];
        return previous.getVisibility() == VISIBLE && previous.picture.getDrawable() != null ? previous : null;
    }
    float snapshotBlend() { return front < 0 ? 1f : layers[front].getAlpha(); }

    boolean controlsVisible() { return controlsVisible; }

    void prepareTrailer(String videoId, TrailerWebView.Listener listener) {
        if (front < 0 || videoId == null) return;
        Layer layer = layers[front];
        layer.removeTrailer();
        TrailerWebView trailer = new TrailerWebView(getContext(), videoId, listener);
        layer.trailer = trailer;
        int index = Math.max(1, layer.getChildCount() - 2);
        layer.addView(trailer, index, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // Keep the hardware video surface out of composition until the first frame is ready.
        // Animating WebView alpha can leave stale decoder tiles at the top edge on Android TV.
        trailer.setVisibility(INVISIBLE);
    }

    void startTrailer() {
        if (front < 0) return;
        TrailerWebView trailer = layers[front].trailer;
        if (trailer != null) trailer.startPlayback();
    }

    void revealTrailer() {
        if (front < 0) return;
        TrailerWebView trailer = layers[front].trailer;
        if (trailer != null) {
            trailer.setAlpha(1f);
            trailer.setVisibility(VISIBLE);
        }
    }

    void fadeOutTrailer(Runnable finished) {
        if (front < 0) { if (finished != null) finished.run(); return; }
        Layer layer = layers[front];
        TrailerWebView trailer = layer.trailer;
        if (trailer == null) { if (finished != null) finished.run(); return; }
        trailer.animate().cancel();
        trailer.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            if (layer.trailer == trailer) layer.removeTrailer();
            if (finished != null) finished.run();
        }).start();
    }

    void stopTrailer() {
        if (front >= 0) layers[front].removeTrailer();
    }

    void setTopTitleVisible(boolean visible, long duration) {
        if (front < 0) return;
        Layer layer = layers[front];
        float alpha = visible ? 1f : 0f;
        layer.logo.animate().cancel();
        layer.title.animate().cancel();
        layer.logo.animate().alpha(alpha).setDuration(duration).start();
        layer.title.animate().alpha(alpha).setDuration(duration).start();
    }

    void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        if (changed != null) changed.run();
    }

    void updateLogo(ArtworkPolicy.Item expected, Bitmap logo) {
        if (front < 0 || item != expected || testBackground != null) return;
        logoPending = false;
        contentRevision++;
        Layer layer = layers[front];
        if (logo != null) {
            sizeLogo(layer.logo, logo);
            layer.logo.setImageBitmap(logo);
            layer.logo.setVisibility(VISIBLE);
            layer.title.setVisibility(GONE);
        } else {
            layer.logo.setVisibility(GONE);
            layer.title.setVisibility(VISIBLE);
        }
        if (changed != null) changed.run();
        notifyVisualChanged();
    }

    void resolvePendingTitle() {
        // If a cancelled first-logo request cannot be replaced, retain a readable title.
        if (logoPending && item != null) updateLogo(item, null);
    }

    private void sizeLogo(ImageView view, Bitmap bitmap) {
        LayoutParams box = (LayoutParams) view.getLayoutParams();
        box.height = Math.max(1, Math.min(dp(110), Math.round(dp(230) * (float) bitmap.getHeight() / bitmap.getWidth())));
        view.setLayoutParams(box);
    }

    int titleBottom() {
        if (front < 0) return dp(154);
        Layer layer = layers[front];
        return (layer.logo.getVisibility() == VISIBLE ? layer.logo : layer.title).getBottom();
    }

    void setArtworkChangedListener(Runnable listener) { changed = listener; }

    void setVisualChangedListener(Runnable listener) { visualChanged = listener; }
    void setDockVisualChangedListener(Runnable listener) { dockVisualChanged = listener; }

    private long lastFrameNotify;
    /** Per-frame callback during a crossfade: 10 fps is plenty for the blurred dock blend. */
    private void notifyVisualChangedThrottled() {
        long now = SystemClock.uptimeMillis();
        if (now - lastFrameNotify < 100) return;
        lastFrameNotify = now;
        notifyVisualChanged();
    }

    private void notifyVisualChanged() {
        if (dockVisualChanged != null) dockVisualChanged.run();
        if (visualChanged != null) visualChanged.run();
    }

    void setArtworkSelected(boolean selected) {
        this.selected = selected;
        if (front < 0) return;
        contentRevision++;
        if (selected) {
            // Settle a crossfade before the user can open its associated title.
            ++transition;
            layers[front].animate().cancel();
            layers[front].setAlpha(1f);
            layers[1 - front].clear();
        }
        for (Layer layer : layers) {
            layer.detailsShade.animate().cancel();
            layer.detailsShade.animate().alpha(selected ? 1f : 0f).setDuration(180).start();
            layer.picture.animate().cancel();
            layer.picture.animate().scaleX(selected ? 1.035f : 1f).scaleY(selected ? 1.035f : 1f)
                    .setDuration(220).setUpdateListener(animation -> notifyVisualChangedThrottled())
                    .withEndAction(() -> {
                        if (front >= 0 && layers[front] == layer) {
                            contentRevision++;
                            notifyVisualChanged();
                        }
                    })
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    void clear() {
        ++transition;
        for (Layer layer : layers) layer.clear();
        front = -1;
        item = null;
        descriptionPending = false;
        if (changed != null) changed.run();
        selected = false;
        notifyVisualChanged();
    }

    private final class Layer extends FrameLayout {
        final ImageView picture, logo;
        final TextView title;
        final View detailsShade;
        TrailerWebView trailer;

        Layer(Context context) {
            super(context);
            picture = new ImageView(context);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(picture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            View bottomShade = new View(context);
            bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0x00000000, 0x08000000, 0x30000000}));
            addView(bottomShade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            View titleShade = new View(context);
            GradientDrawable shade = new GradientDrawable();
            shade.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            shade.setGradientCenter(0f, 0f);
            shade.setGradientRadius(dp(450));
            shade.setColors(new int[]{0x66000000, 0x00000000});
            titleShade.setBackground(shade);
            addView(titleShade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            detailsShade = new View(context);
            detailsShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xA6000000, 0x47000000, 0x00000000}));
            detailsShade.setAlpha(0f);
            addView(detailsShade, new LayoutParams(dp(550), LayoutParams.MATCH_PARENT));

            logo = new ImageView(context);
            logo.setScaleType(ImageView.ScaleType.FIT_START);
            LayoutParams logoBox = new LayoutParams(dp(230), dp(110), Gravity.TOP | Gravity.START);
            logoBox.setMargins(dp(40), dp(36), 0, 0);
            addView(logo, logoBox);

            title = new TextView(context);
            title.setTextColor(Color.WHITE);
            title.setTextSize(34);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setMaxLines(3);
            title.setIncludeFontPadding(false);
            title.setShadowLayer(dp(4), 0, dp(1), 0x66000000);
            LayoutParams titleBox = new LayoutParams(dp(380), LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            titleBox.setMargins(dp(40), dp(36), 0, 0);
            addView(title, titleBox);
            OnLayoutChangeListener placeDetails = (v, l, t, r, b, ol, ot, or, ob) -> {
                if (item != null && front >= 0 && layers[front] == this && (b != ob || t != ot)) {
                    contentRevision++;
                    if (changed != null) changed.run();
                    notifyVisualChanged();
                }
            };
            logo.addOnLayoutChangeListener(placeDetails);
            title.addOnLayoutChangeListener(placeDetails);
            clear();
        }

        void clear() {
            animate().cancel();
            picture.animate().cancel();
            detailsShade.animate().cancel();
            logo.animate().cancel();
            title.animate().cancel();
            removeTrailer();
            detailsShade.setAlpha(0f);
            logo.setAlpha(1f);
            title.setAlpha(1f);
            picture.setScaleX(1f);
            picture.setScaleY(1f);
            setVisibility(GONE);
            setAlpha(0f);
            picture.setImageDrawable(null);
            logo.setImageDrawable(null);
            title.setText(null);
        }

        void removeTrailer() {
            TrailerWebView current = trailer;
            trailer = null;
            if (current != null) {
                current.animate().cancel();
                removeView(current);
                current.release();
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
