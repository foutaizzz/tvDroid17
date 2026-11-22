package com.tvdroid17;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;

/** Focus changes the row surface while preserving its original size and shape. */
final class SettingsRow extends LinearLayout {
    private final float density;
    private final boolean twoLines;
    private final GradientDrawable surface = new GradientDrawable();
    private final Path clipPath = new Path();
    private final RectF clipBounds = new RectF();
    private float focus;
    private ValueAnimator animation;

    SettingsRow(Context context, boolean twoLines) {
        super(context);
        this.twoLines = twoLines;
        density = getResources().getDisplayMetrics().density;
        surface.setShape(GradientDrawable.RECTANGLE);
        surface.setColor(0xFF3A3A3C);
        surface.setCornerRadius(dp(twoLines ? 9 : 8));
        setBackground(surface);
        setClipChildren(false);
        setClipToPadding(false);
        setDefaultFocusHighlightEnabled(false);
        setStateListAnimator(null);
        setClipToOutline(true);
        setForeground(null);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            setOutlineAmbientShadowColor(0x55000000);
            setOutlineSpotShadowColor(0x33000000);
        }
    }

    void highlight(boolean selected) {
        if (animation != null) animation.cancel();
        animation = ValueAnimator.ofFloat(focus, selected ? 1f : 0f);
        animation.setDuration(160);
        animation.setInterpolator(new PathInterpolator(.22f, 1, .36f, 1));
        animation.addUpdateListener(value -> {
            focus = (float) value.getAnimatedValue();
            int red = Math.round(58 + (255 - 58) * focus);
            int green = Math.round(58 + (255 - 58) * focus);
            int blue = Math.round(60 + (255 - 60) * focus);
            surface.setColor(Color.rgb(red, green, blue));
            surface.setCornerRadius(dp(twoLines ? 9 : 8));
        });
        animation.start();
    }

    private float dp(float value) { return value * density; }

    @Override public void draw(Canvas canvas) {
        clipBounds.set(0, 0, getWidth(), getHeight());
        clipPath.reset();
        float radius = dp(twoLines ? 9 : 8);
        clipPath.addRoundRect(clipBounds, radius, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clipPath);
        super.draw(canvas);
        canvas.restoreToCount(save);
    }

    @Override protected void onDetachedFromWindow() {
        if (animation != null) animation.cancel();
        super.onDetachedFromWindow();
    }
}
