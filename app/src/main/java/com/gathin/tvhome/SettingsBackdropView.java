package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Uniform dark settings background, independent of the current movie. */
final class SettingsBackdropView extends View {
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);

    SettingsBackdropView(Context context) { super(context); }

    @Override protected void onDraw(Canvas canvas) {
        base.setColor(0xFF1B1B1D);
        canvas.drawRect(0, 0, getWidth(), getHeight(), base);
    }
}

