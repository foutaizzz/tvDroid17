package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

/** A launcher-owned settings entrance. Protected device settings remain native Android panels. */
final class SettingsMenu {
    private static final String[] TITLES = {"Image et affichage", "Son", "Économiseur d’écran",
            "Applications", "Réseau", "Télécommandes et accessoires", "Sous-titres", "Système"};
    private static final String[] DETAILS = {"Ajustez l’image et les préférences d’affichage de votre téléviseur.",
            "Choisissez vos sorties audio et les réglages du son.", "Personnalisez l’écran de veille du téléviseur.",
            "Gérez les applications installées et leurs autorisations.", "Retrouvez votre connexion Wi-Fi et les réseaux disponibles.",
            "Associez une télécommande, une manette ou un appareil Bluetooth.", "Personnalisez les sous-titres et leur présentation sur le téléviseur.",
            "Accédez à l’ensemble des réglages Android du téléviseur."};
    private static final int[] ICONS = {R.drawable.ic_settings, R.drawable.ic_sound, R.drawable.ic_timer,
            R.drawable.ic_apps, R.drawable.ic_wifi, R.drawable.ic_gamepad, R.drawable.ic_accessibility, R.drawable.ic_controls};
    private final Activity activity;
    private final View anchor;
    private final float density;
    private final PopupWindow popup;
    private final FrameLayout content;
    private final View background;
    private final View[] rows = new View[TITLES.length];
    private final TextView description;
    private final ImageView illustration;
    private ArtworkBackdropView artwork;

    SettingsMenu(Activity activity, View anchor, Runnable[] actions) {
        this.activity = activity;
        this.anchor = anchor;
        density = activity.getResources().getDisplayMetrics().density;
        FrameLayout overlay = new FrameLayout(activity);
        background = new SettingsBackdropView(activity);
        overlay.addView(background, new FrameLayout.LayoutParams(-1, -1));
        content = new FrameLayout(activity);
        content.setClipChildren(false);
        FrameLayout.LayoutParams design = new FrameLayout.LayoutParams(dp(960), dp(540), Gravity.CENTER);
        overlay.addView(content, design);
        overlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            float scale = Math.min((r - l) / (960 * density), (b - t) / (540 * density));
            content.setScaleX(scale); content.setScaleY(scale);
        });
        TextView title = text("Réglages", 28);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(0xFFE5E5EA);
        title.setGravity(Gravity.CENTER);
        place(title, 0, 26, 960, 40);

        FrameLayout card = new FrameLayout(activity);
        GradientDrawable cardSurface = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF3A3A3C, 0xFF242426});
        cardSurface.setCornerRadius(dp(62));
        card.setBackground(cardSurface);
        place(card, 128, 118, 270, 270);
        illustration = new ImageView(activity);
        illustration.setImageResource(R.drawable.ic_controls);
        illustration.setColorFilter(0xFFB8B8BE);
        FrameLayout.LayoutParams symbol = new FrameLayout.LayoutParams(dp(82), dp(82), Gravity.CENTER);
        card.addView(illustration, symbol);
        description = text(DETAILS[0], 13);
        description.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        description.setMaxLines(4);
        description.setLineSpacing(dp(3), 1f);
        place(description, 92, 412, 342, 90);

        popup = new PopupWindow(overlay, -1, -1, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            content.animate().cancel();
            description.animate().cancel(); illustration.animate().cancel();
            if (artwork != null) {
                artwork.setControlsVisible(false);
            }
            artwork = null;
            anchor.requestFocus();
        });
        for (int n = 0; n < rows.length; n++) {
            final int index = n;
            FrameLayout row = new FrameLayout(activity);
            row.setFocusable(true); row.setFocusableInTouchMode(true); row.setClickable(true);
            row.setContentDescription(TITLES[n]);
            row.setBackground(surface(0x263A3A3C, 17));
            row.setClipChildren(false);
            TextView label = text(TITLES[n], 18);
            label.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams labelBox = new FrameLayout.LayoutParams(-1, -1);
            labelBox.setMargins(dp(16), 0, dp(32), 0);
            row.addView(label, labelBox);
            ImageView arrow = new ImageView(activity);
            arrow.setImageResource(R.drawable.ic_chevron_right);
            FrameLayout.LayoutParams arrowBox = new FrameLayout.LayoutParams(dp(14), dp(20), Gravity.END | Gravity.CENTER_VERTICAL);
            arrowBox.rightMargin = dp(14);
            row.addView(arrow, arrowBox);
            row.setOnFocusChangeListener((v, focused) -> {
                row.setBackground(surface(focused ? 0xFFF2F2F7 : 0x263A3A3C, 17));
                row.setElevation(focused ? dp(8) : 0);
                row.animate().scaleX(focused ? 1.025f : 1f).scaleY(focused ? 1.025f : 1f)
                        .setDuration(160).setInterpolator(new PathInterpolator(.22f, 1f, .36f, 1f)).start();
                if (focused) {
                    illustration.setImageResource(ICONS[index]);
                    description.setText(DETAILS[index]);
                }
            });
            row.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int next = Math.max(0, Math.min(rows.length - 1, index + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                    rows[next].requestFocus(); return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
                return false;
            });
            row.setOnClickListener(v -> {
                dismiss();
                if (index < actions.length && actions[index] != null) anchor.post(actions[index]);
            });
            place(row, 530, 94 + n * 40, 390, 34);
            rows[n] = row;
        }
    }

    void show(View root) {
        if (popup.isShowing()) return;
        artwork = activity.findViewById(R.id.artwork);
        artwork.setControlsVisible(true);
        popup.showAtLocation(root, Gravity.FILL, 0, 0);
        content.animate().cancel();
        content.setAlpha(0f); content.setTranslationX(dp(12));
        content.animate().alpha(1f).translationX(0f).setDuration(200)
                .setInterpolator(new PathInterpolator(.22f, 1f, .36f, 1f)).start();
        rows[0].requestFocus();
        rows[0].post(() -> { if (popup.isShowing()) rows[0].requestFocus(); });
    }

    void dismiss() { if (popup.isShowing()) popup.dismiss(); }

    private TextView text(String value, float size) {
        TextView text = new TextView(activity);
        text.setText(value); text.setTextSize(size); text.setTextColor(0xFFF2F2F7);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setIncludeFontPadding(false);
        return text;
    }

    private GradientDrawable surface(int color, float radius) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(color); surface.setCornerRadius(dp(radius));
        return surface;
    }

    private void place(View view, int x, int y, int width, int height) {
        FrameLayout.LayoutParams box = new FrameLayout.LayoutParams(dp(width), dp(height));
        box.leftMargin = dp(x); box.topMargin = dp(y); content.addView(view, box);
    }

    private int dp(float value) { return Math.round(value * density); }
}

