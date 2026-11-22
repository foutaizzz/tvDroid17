package com.tvdroid17;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** A small RAM-only backdrop shared by the separate glass controls; never saved. */
final class FrostedControls {
    static final int CONTROL_BUTTON_TINT = 0xF00B0C08;
    private Bitmap backdrop;
    private Bitmap previous;
    private Canvas previousCanvas;
    private float blend = 1f;
    private ArtworkBackdropView capturedOwner;
    private long capturedFrame = -1, capturedContent = -1;
    private int rootWidth, rootHeight;
    private final int[] origin = new int[2];
    private RenderScript script;
    private Allocation input, output;
    private ScriptIntrinsicBlur blur;
    private Canvas captureCanvas;
    private final List<Drawable> materials = new ArrayList<>();
    private int surfaceColor = 0xC91C1C1E;
    void setSurfaceColor(int color) { surfaceColor = color; invalidateMaterials(); }
    private final Paint fullPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    void captureArtwork(ArtworkBackdropView artwork) {
        if (artwork.currentItem() == null) { clear(); return; }
        View source = artwork.snapshotSource();
        if (source.getWidth() == 0 || source.getHeight() == 0) return;
        boolean newFrame = capturedOwner != artwork || capturedFrame != artwork.frameRevision();
        if (newFrame || capturedContent != artwork.contentRevision() || backdrop == null) {
            if (newFrame && backdrop == null && artwork.snapshotBlend() < 1f) {
                // A newly opened control centre can arrive halfway through a fade.
                // It needs both endpoints, just like the dock that was already visible.
                View outgoing = artwork.previousSnapshotSource();
                if (outgoing != null && outgoing.getWidth() > 0 && outgoing.getHeight() > 0) capture(outgoing);
            }
            if (newFrame && backdrop != null) {
                if (previous == null || previous.getWidth() != backdrop.getWidth() || previous.getHeight() != backdrop.getHeight()) {
                    previous = Bitmap.createBitmap(backdrop.getWidth(), backdrop.getHeight(), Bitmap.Config.ARGB_8888);
                    previousCanvas = new Canvas(previous);
                }
                previousCanvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
                previousCanvas.drawBitmap(backdrop, 0, 0, fullPaint);
            }
            capture(source);
            capturedOwner = artwork;
            capturedFrame = artwork.frameRevision();
            capturedContent = artwork.contentRevision();
        }
        blend = artwork.snapshotBlend();
        invalidateMaterials();
    }

    void capture(View root) {
        rootWidth = root.getWidth();
        rootHeight = root.getHeight();
        if (rootWidth == 0 || rootHeight == 0) return;
        root.getLocationOnScreen(origin);
        // The material is already blurred over ~40 screen pixels. Quarter as many samples
        // preserve that visual radius while avoiding long UI-thread capture/blur stalls.
        int width = 240, height = Math.max(1, Math.round(width * rootHeight / (float) rootWidth));
        if (backdrop == null || backdrop.getWidth() != width || backdrop.getHeight() != height) {
            releaseBlur();
            backdrop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            captureCanvas = new Canvas(backdrop);
            try {
                script = RenderScript.create(root.getContext());
                input = Allocation.createFromBitmap(script, backdrop);
                output = Allocation.createTyped(script, input.getType());
                blur = ScriptIntrinsicBlur.create(script, Element.U8_4(script));
                blur.setRadius(3f);
                blur.setInput(input);
            } catch (RuntimeException unsupported) {
                releaseBlur();
            }
        }
        captureCanvas.save();
        captureCanvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        captureCanvas.scale(width / (float) rootWidth, height / (float) rootHeight);
        root.draw(captureCanvas);
        captureCanvas.restore();
        try {
            if (blur != null) {
                input.copyFrom(backdrop);
                blur.forEach(output);
                output.copyTo(backdrop);
            }
        } catch (RuntimeException unsupported) {
            releaseBlur();
        }
        invalidateMaterials();
    }

    private void releaseBlur() {
        if (blur != null) blur.destroy();
        if (input != null) input.destroy();
        if (output != null) output.destroy();
        if (script != null) script.destroy();
        blur = null;
        input = output = null;
        script = null;
    }

    private void invalidateMaterials() {
        // Drop materials whose view is gone (a dismissed control panel): keeping them would leak
        // the whole old panel and make this loop grow on every opening.
        java.util.Iterator<Drawable> it = materials.iterator();
        while (it.hasNext()) {
            Drawable material = it.next();
            if (material instanceof Material) {
                Material m = (Material) material;
                if (m.ownerGone()) { it.remove(); continue; }
                // Invalidate up to the window root: on this TV, invalidating only the owner view
                // (or relying on the drawable callback) does not repaint views outside the grid
                // (status capsule) or inside a popup (control panel) at boot or on slide changes.
                View owner = m.ownerRef.get();
                if (owner != null) {
                    View root = owner;
                    while (root.getParent() instanceof View) root = (View) root.getParent();
                    root.invalidate();
                }
            }
            material.invalidateSelf();
        }
    }

    boolean hasBackdrop() { return backdrop != null; }

    void clear() {
        releaseBlur();
        backdrop = null;
        previous = null;
        previousCanvas = null;
        capturedOwner = null;
        capturedFrame = capturedContent = -1;
        blend = 1f;
        captureCanvas = null;
        invalidateMaterials();
    }

    Drawable material(View owner, float radius) { return material(owner, radius, surfaceColor, true); }

    Drawable material(View owner, float radius, boolean respondsToFocus) {
        return material(owner, radius, surfaceColor, respondsToFocus);
    }

    Drawable material(View owner, float radius, int tintColor) {
        return material(owner, radius, tintColor, true);
    }

    Drawable material(View owner, float radius, int tintColor, boolean respondsToFocus) {
        Drawable material = new Material(owner, radius, tintColor, respondsToFocus);
        materials.add(material);
        return material;
    }

    private final class Material extends Drawable {
        // Weak so a dismissed panel does not stay alive just because its material is still listed.
        private final java.lang.ref.WeakReference<View> ownerRef;
        private final float radius;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path path = new Path();
        private final RectF area = new RectF();
        private final Rect source = new Rect();
        private final int[] location = new int[2];

        private final int tintColor;
        private final boolean respondsToFocus;
        Material(View owner, float radius, int tintColor, boolean respondsToFocus) {
            this.ownerRef = new java.lang.ref.WeakReference<>(owner);
            this.radius = radius;
            this.tintColor = tintColor;
            this.respondsToFocus = respondsToFocus;
        }

        boolean ownerGone() {
            View owner = ownerRef.get();
            return owner == null || !owner.isAttachedToWindow();
        }

        @Override public void draw(Canvas canvas) {
            View owner = ownerRef.get();
            if (owner == null) return;
            area.set(getBounds());
            area.inset(0.75f, 0.75f);
            path.reset();
            path.addRoundRect(area, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(path);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            if (backdrop != null) {
                owner.getLocationOnScreen(location);
                float sx = backdrop.getWidth() / (float) rootWidth;
                float sy = backdrop.getHeight() / (float) rootHeight;
                source.set(Math.round((location[0] - origin[0] + area.left) * sx),
                        Math.round((location[1] - origin[1] + area.top) * sy),
                        Math.round((location[0] - origin[0] + area.right) * sx),
                        Math.round((location[1] - origin[1] + area.bottom) * sy));
                paint.setColor(0xFFFFFFFF);
                if (previous != null && blend < 1f) canvas.drawBitmap(previous, source, area, paint);
                paint.setAlpha(Math.round(255 * blend));
                canvas.drawBitmap(backdrop, source, area, paint);
                paint.setAlpha(255);
            }
            boolean focused = respondsToFocus && owner.isFocused();
            paint.setColor(focused ? 0xFFF1F1E9 : tintColor);
            canvas.drawRect(area, paint);
            paint.setShader(new LinearGradient(0, area.top, 0, area.bottom,
                    focused ? 0x2FFFFFFF : 0x10FFFFFF,
                    focused ? 0x12FFFFFF : 0x03FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(area, paint);
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(owner.getResources().getDisplayMetrics().density * (focused ? 1.1f : 0.65f));
            paint.setShader(new LinearGradient(area.left, area.top, area.right, area.bottom,
                    0x00000000, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(area, radius, radius, paint);
            paint.setShader(null);
        }

        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}













