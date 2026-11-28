package com.tvdroid17;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
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
        boolean newFrame = capturedOwner != artwork || capturedFrame != artwork.frameRevision();
        boolean captured = false;
        if (newFrame || capturedContent != artwork.contentRevision() || backdrop == null) {
            if (newFrame && backdrop == null && artwork.snapshotBlend() < 1f) {
                // A newly opened control centre can arrive halfway through a fade.
                // It needs both endpoints, just like the dock that was already visible.
                View outgoing = artwork.previousSnapshotSource();
                if (outgoing != null && outgoing.getWidth() > 0 && outgoing.getHeight() > 0) {
                    capture(outgoing);
                    keepAsPrevious();
                }
            }
            if (newFrame && backdrop != null) keepAsPrevious();
            // One capture per slide, of the artwork as it will be once the fade is over. The
            // glass then fades between the two images, exactly like the artwork itself, and
            // no view is captured on the UI thread while the fade runs.
            artwork.captureOpaque(this);
            capturedOwner = artwork;
            capturedFrame = artwork.frameRevision();
            capturedContent = artwork.contentRevision();
            captured = true;
        }
        blend = artwork.snapshotBlend();
        if (captured) invalidateMaterials();
        else {
            // Only the blend moved: repaint the glass surfaces alone. Invalidating the whole
            // window on every frame of the fade would redraw the artwork with it.
            refreshMaterials();
        }
    }

    /** Keeps the current backdrop as the first image of the glass's blend. */
    private void keepAsPrevious() {
        if (backdrop == null) return;
        if (previous == null || previous.getWidth() != backdrop.getWidth() || previous.getHeight() != backdrop.getHeight()) {
            previous = Bitmap.createBitmap(backdrop.getWidth(), backdrop.getHeight(), Bitmap.Config.ARGB_8888);
            previousCanvas = new Canvas(previous);
        }
        previousCanvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        previousCanvas.drawBitmap(backdrop, 0, 0, fullPaint);
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

    /** Repaints the glass surfaces alone, without touching the rest of the window. */
    private void refreshMaterials() {
        for (Drawable material : materials) material.invalidateSelf();
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

    /**
     * Fills a surface with a sampled region of a backdrop bitmap. The shader is kept and only
     * rebound when the bitmap changes, so drawing the frosted surfaces does not allocate.
     */
    private static final class SurfaceShader {
        private final Matrix matrix = new Matrix();
        BitmapShader shader;
        private Bitmap bitmap;

        void bind(Bitmap source, Rect src, RectF dst) {
            if (shader == null || bitmap != source) {
                shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bitmap = source;
            }
            // The local matrix of a shader maps the bitmap onto the surface, exactly like a
            // drawBitmap(bitmap, src, dst) would: canvas = scale * bitmap + offset.
            float kx = dst.width() / src.width();
            float ky = dst.height() / src.height();
            matrix.setValues(new float[]{
                    kx, 0f, dst.left - kx * src.left,
                    0f, ky, dst.top - ky * src.top,
                    0f, 0f, 1f});
            shader.setLocalMatrix(matrix);
        }
    }

    private final class Material extends Drawable {        // Weak so a dismissed panel does not stay alive just because its material is still listed.
        private final java.lang.ref.WeakReference<View> ownerRef;
        private final float radius;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF area = new RectF();
        private final Rect source = new Rect();
        private final int[] location = new int[2];
        // The rounded corners come from the geometry of the draw, not from a path clip.
        private final SurfaceShader backdropSurface = new SurfaceShader();
        private final SurfaceShader previousSurface = new SurfaceShader();
        private Shader veil;
        private boolean veilFocused;
        private float veilTop = Float.NaN, veilBottom = Float.NaN;

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
            if (area.width() <= 0f || area.height() <= 0f) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            if (backdrop != null && rootWidth > 0 && rootHeight > 0) {
                owner.getLocationOnScreen(location);
                float sx = backdrop.getWidth() / (float) rootWidth;
                float sy = backdrop.getHeight() / (float) rootHeight;
                source.set(Math.round((location[0] - origin[0] + area.left) * sx),
                        Math.round((location[1] - origin[1] + area.top) * sy),
                        Math.round((location[0] - origin[0] + area.right) * sx),
                        Math.round((location[1] - origin[1] + area.bottom) * sy));
                paint.setColor(0xFFFFFFFF);
                // The surface is painted as a rounded rectangle filled with a shader, instead of
                // being clipped to a rounded path: a path clip makes the renderer allocate an
                // offscreen target on every frame, which showed up as jank on this television.
                if (source.width() > 0 && source.height() > 0) {
                    if (previous != null && blend < 1f) {
                        previousSurface.bind(previous, source, area);
                        paint.setShader(previousSurface.shader);
                        canvas.drawRoundRect(area, radius, radius, paint);
                    }
                    paint.setAlpha(Math.round(255 * blend));
                    backdropSurface.bind(backdrop, source, area);
                    paint.setShader(backdropSurface.shader);
                    canvas.drawRoundRect(area, radius, radius, paint);
                    paint.setAlpha(255);
                }
            }
            paint.setShader(null);
            boolean focused = respondsToFocus && owner.isFocused();
            paint.setColor(focused ? 0xFFF1F1E9 : tintColor);
            canvas.drawRoundRect(area, radius, radius, paint);
            // The veil is cached: the same gradient was rebuilt on every frame of every
            // surface, and the band it covers is redrawn as often as the artwork animates.
            paint.setShader(veil(focused));
            canvas.drawRoundRect(area, radius, radius, paint);
            paint.setShader(null);
        }

        /** The white veil over the frosted surface, rebuilt only when its bounds or its
         *  focus state change. */
        private Shader veil(boolean focused) {
            if (veil == null || veilFocused != focused || veilTop != area.top || veilBottom != area.bottom) {
                veil = new LinearGradient(0, area.top, 0, area.bottom,
                        focused ? 0x2FFFFFFF : 0x10FFFFFF,
                        focused ? 0x12FFFFFF : 0x03FFFFFF, Shader.TileMode.CLAMP);
                veilFocused = focused;
                veilTop = area.top;
                veilBottom = area.bottom;
            }
            return veil;
        }

        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}













