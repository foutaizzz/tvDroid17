package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/** Separate glass controls matching the asymmetric tvOS reference, without an enclosing card. */
final class ControlPanel {
    static final int POWER = 0, SETTINGS = 1, WIFI = 2, AUDIO = 3, TIMER = 4,
            DEVICES = 5, CAPTIONS = 6, SOURCES = 7, APPS = 8, HOME = 9, CONTROL = 10,
            APP_INFO = 11; // not a visible tile: long-press on APPS opens the focused app's details
    private final Activity activity;
    private final float density;
    private final View anchor;
    private final PopupWindow popup;
    private final FrameLayout sheet;
    private final FrameLayout controls;
    private final View[] buttons = new View[12];
    private final FrostedControls glass;
    private final boolean ownsGlass;
    private ArtworkBackdropView artwork;
    private final View[] hidden = new View[4];
    private final int[] previousVisibility = new int[4];
    private View previousFocus;
    private Runnable onClosed;

    ControlPanel(Activity activity, View anchor, Runnable[] actions, String wifiName) {
        this(activity, anchor, actions, wifiName, null);
    }

    /** @param sharedGlass the dock's glass, so the panel background is the dock's live blur;
     *                    null keeps a private glass (used by tests). */
    ControlPanel(Activity activity, View anchor, Runnable[] actions, String wifiName, FrostedControls sharedGlass) {
        this.ownsGlass = sharedGlass == null;
        this.glass = sharedGlass != null ? sharedGlass : new FrostedControls();
        this.activity = activity;
        this.anchor = anchor;
        density = activity.getResources().getDisplayMetrics().density;
        FrameLayout overlay = new FrameLayout(activity);
        // Keep the page behind the controls unchanged; the Inputs panel uses the same approach.
        overlay.setBackgroundColor(Color.TRANSPARENT);
        overlay.setOnClickListener(v -> dismiss());
        sheet = new FrameLayout(activity);
        sheet.setClipChildren(false);
        sheet.setClipToPadding(false);
        controls = new FrameLayout(activity);
        controls.setClipChildren(false);
        controls.setClipToPadding(false);
        controls.setPivotX(dp(250));
        controls.setPivotY(0f);
        controls.setScaleX(.96f);
        controls.setScaleY(.94f);
        sheet.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout panel = new FrameLayout(activity);
        panel.setBackground(glass.material(panel, dp(20), false));
        put(panel, 0, 32, 250, 254);
        FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(dp(250), dp(286), Gravity.TOP | Gravity.END);
        place.setMargins(0, dp(36), dp(32), 0);
        overlay.addView(sheet, place);

        // Invisible focus proxy: keep the real status capsule visible and fixed in place.
        View controlFocus = new View(activity);
        prepare(controlFocus, CONTROL, "Centre de controle", null, 0);
        controlFocus.setBackgroundColor(Color.TRANSPARENT);
        controlFocus.setAlpha(0f);
        sheet.addView(controlFocus, new FrameLayout.LayoutParams(dp(28), dp(28)));
        tile(POWER, "Alimentation", null, R.drawable.ic_power, actions[POWER], 12, 44, 109, 112, true);
        tile(SETTINGS, "Réglages", null, R.drawable.ic_settings, actions[SETTINGS], 129, 104, 109, 52, false);
        tile(WIFI, "Wi-Fi", wifiName, R.drawable.ic_wifi, actions[WIFI], 129, 44, 109, 52, false);
        tile(AUDIO, "Audio", null, R.drawable.ic_sound, actions[AUDIO], 12, 164, 109, 52, false);
        tile(TIMER, "Minuteur", null, R.drawable.ic_timer, actions[TIMER], 129, 164, 109, 52, false);
        circle(DEVICES, "Manettes et accessoires Bluetooth", R.drawable.ic_gamepad, actions[DEVICES], 13);
        circle(CAPTIONS, "Sous-titres", R.drawable.ic_accessibility, actions[CAPTIONS], 71);
        circle(SOURCES, "Sources HDMI et AV", R.drawable.ic_input, actions[SOURCES], 129);
        circle(APPS, "Applications (appui long : infos de l’app sélectionnée)", R.drawable.ic_apps, actions[APPS], 187);

        for (int i = 0; i < buttons.length; i++) {
            final int index = i;
            if (buttons[i] == null) continue;
            buttons[i].setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int direction;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) direction = ControlNavigation.LEFT;
                else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) direction = ControlNavigation.RIGHT;
                else if (key == KeyEvent.KEYCODE_DPAD_UP) direction = ControlNavigation.UP;
                else if (key == KeyEvent.KEYCODE_DPAD_DOWN) direction = ControlNavigation.DOWN;
                else return false;
                buttons[ControlNavigation.next(index, direction)].requestFocus();
                return true;
            });
        }
        // Long-press on the Applications tile: open the system details of the app currently
        // focused in the grid (the tile long-press itself now moves the tile).
        if (buttons[APPS] != null) buttons[APPS].setOnLongClickListener(v -> {
            dismiss();
            if (actions.length > APP_INFO && actions[APP_INFO] != null) anchor.post(actions[APP_INFO]);
            return true;
        });
        popup = new PopupWindow(overlay, -1, -1, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(0);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            if (artwork != null) artwork.setVisualChangedListener(null);
            artwork = null;
            // Only clear a private glass; a shared one must keep the dock's blur alive.
            if (ownsGlass) glass.clear();
            sheet.animate().cancel();
            sheet.setAlpha(1f);
            sheet.setScaleX(1f);
            sheet.setScaleY(1f);
            sheet.setTranslationY(0f);
            for (int i = 0; i < hidden.length; i++)
                if (hidden[i] != null) hidden[i].setVisibility(previousVisibility[i]);
            ArtworkBackdropView currentArtwork = activity.findViewById(R.id.artwork);
            if (currentArtwork != null) {
                currentArtwork.setControlsVisible(false);
                // hidden[] is filled in show(); a dismiss that wins the race before show() must not crash.
                if (hidden[0] != null) hidden[0].setVisibility(PlayTorrioDetails.isInstalled(activity)
                    && PlayTorrioDetails.supports(currentArtwork.currentItem())
                    ? View.VISIBLE : View.GONE);
                if (hidden[3] != null) hidden[3].setVisibility(View.INVISIBLE);
                View hint = activity.findViewById(R.id.artworkHint);
                if (hint != null) hint.setVisibility(View.INVISIBLE);
            }
            if (onClosed != null) onClosed.run();
            // PopupWindow can still own window focus while its dismiss callback runs.
            // Restore the selection that was active before the panel opened.
            anchor.postDelayed(() -> {
                if (popup.isShowing()) return;
                View target = previousFocus;
                if (target == null || !target.isShown() || !target.isFocusable()
                        || !target.requestFocus()) {
                    anchor.requestFocus();
                }
            }, 120);
        });
    }

    private void tile(int index, String label, String detail, int icon, Runnable action,
                      int x, int y, int width, int height, boolean large) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(large ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(large ? 6 : 12), 0, dp(large ? 6 : 9), 0);
        prepare(tile, index, label, action, large ? 12 : 9);
        ImageView image = icon(icon, index == WIFI ? 0xFF42A5FF : Color.WHITE);
        int size = large ? 54 : 24;
        tile.addView(image, new LinearLayout.LayoutParams(dp(size), dp(size)));
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(label, large ? 12 : 13);
        title.setMaxLines(2);
        texts.addView(title);
        if (detail != null && !detail.isEmpty()) {
            TextView subtitle = text(detail, 11);
            subtitle.setTextColor(0xCFFFFFFF);
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(subtitle);
        }
        LinearLayout.LayoutParams caption = new LinearLayout.LayoutParams(large ? -2 : 0, -2, large ? 0 : 1);
        if (large) caption.topMargin = dp(10); else caption.leftMargin = dp(8);
        tile.addView(texts, caption);
        tile.setOnFocusChangeListener((v, focused) -> {
            image.setColorFilter(focused ? FrostedControls.CONTROL_BUTTON_TINT : (index == WIFI ? 0xFF42A5FF : Color.WHITE));
            title.setTextColor(focused ? FrostedControls.CONTROL_BUTTON_TINT : Color.WHITE);
            if (texts.getChildCount() > 1)
                ((TextView) texts.getChildAt(1)).setTextColor(focused ? 0xCC0B0C08 : 0xCFFFFFFF);
        });
        put(tile, x, y, width, height);
    }

    private void circle(int index, String label, int icon, Runnable action, int x) {
        FrameLayout square = new FrameLayout(activity);
        prepare(square, index, label, action, 11);
        ImageView image = icon(icon, Color.WHITE);
        square.addView(image, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        square.setOnFocusChangeListener((v, focused) -> {
            image.setColorFilter(focused ? FrostedControls.CONTROL_BUTTON_TINT : Color.WHITE);
            v.invalidate();
        });
        put(square, x, 224, 50, 50);
    }

    private void prepare(View view, int index, String label, Runnable action, int radius) {
        buttons[index] = view;
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setClickable(true);
        view.setContentDescription(label);
        view.setBackground(glass.material(view, dp(radius), FrostedControls.CONTROL_BUTTON_TINT));
        view.setOnFocusChangeListener((v, focused) -> v.invalidate());
        view.setOnClickListener(v -> {
            dismiss();
            if (action != null) anchor.post(action);
        });
    }

    void show(View root) {
        if (popup.isShowing()) return;
        previousFocus = activity.getCurrentFocus();
        View capsule = (View) anchor.getParent();
        int[] capsuleOrigin = new int[2], rootOrigin = new int[2];
        capsule.getLocationOnScreen(capsuleOrigin);
        root.getLocationOnScreen(rootOrigin);
        FrameLayout.LayoutParams sheetBox = (FrameLayout.LayoutParams) sheet.getLayoutParams();
        sheetBox.rightMargin = Math.max(0, rootOrigin[0] + root.getWidth()
                - capsuleOrigin[0] - capsule.getWidth());
        sheet.setLayoutParams(sheetBox);
        artwork = activity.findViewById(R.id.artwork);
        if (artwork != null) artwork.setControlsVisible(true);
        // Remove the artwork focus target before hiding the focused top bar.
        // Otherwise Android can select the artwork behind the popup and hold its slideshow.
        hidden[0] = activity.findViewById(R.id.artworkTarget);
        hidden[1] = activity.findViewById(R.id.grid);
        hidden[2] = null; // Keep the original clock/settings capsule stationary.
        hidden[3] = activity.findViewById(R.id.artworkDetails);
        for (int i = 0; i < hidden.length; i++) {
            if (hidden[i] != null) previousVisibility[i] = hidden[i].getVisibility();
        }
        for (int i = 0; i < hidden.length; i++) {
            // Keep applications visible behind controls, as on the reference home screen.
            if (i != 1 && hidden[i] != null) hidden[i].setVisibility(View.INVISIBLE);
        }
        if (artwork != null) {
            activity.findViewById(R.id.artworkHint).setVisibility(View.INVISIBLE);
            glass.captureArtwork(artwork);
        }
        popup.showAtLocation(root, Gravity.FILL, 0, 0);
        sheet.animate().cancel();
        sheet.setPivotY(0f);
        sheet.setAlpha(0f);
        sheet.setScaleY(.92f);
        sheet.setTranslationY(-dp(8));
        sheet.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        if (artwork != null) artwork.setVisualChangedListener(this::onArtworkVisualChanged);
        // Opening keeps focus on the closed control-center button, not a panel tile.
        int[] anchorOrigin = new int[2], sheetOrigin = new int[2];
        anchor.getLocationOnScreen(anchorOrigin);
        sheet.getLocationOnScreen(sheetOrigin);
        FrameLayout.LayoutParams focusBox = (FrameLayout.LayoutParams) buttons[CONTROL].getLayoutParams();
        focusBox.leftMargin = anchorOrigin[0] - sheetOrigin[0];
        focusBox.topMargin = anchorOrigin[1] - sheetOrigin[1];
        buttons[CONTROL].setLayoutParams(focusBox);
        buttons[CONTROL].requestFocus();
        sheet.postDelayed(() -> {
            if (popup.isShowing()) buttons[CONTROL].requestFocus();
        }, 120);
    }

    private void onArtworkVisualChanged() {
        if (!popup.isShowing()) return;
        // The panel's materials are created before the popup is first shown, so the shared
        // glass may have dropped them (owner not attached yet); repaint the whole popup window
        // explicitly so its frosted background always follows the current artwork.
        popup.getContentView().invalidate();
        // Metadata updates can restore this target; keep it out of focus behind the popup.
        if (hidden[0] != null) hidden[0].setVisibility(View.INVISIBLE);
        if (hidden[3] != null) hidden[3].setVisibility(View.INVISIBLE);
        View hint = activity.findViewById(R.id.artworkHint);
        if (hint != null) hint.setVisibility(View.INVISIBLE);
        if (artwork != null) glass.captureArtwork(artwork);
    }

    boolean isShowing() { return popup.isShowing(); }
    void dismiss() { popup.dismiss(); }
    void setOnClosed(Runnable listener) { onClosed = listener; }

    private void put(View view, int x, int y, int width, int height) {
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(dp(width), dp(height));
        layout.leftMargin = dp(x);
        layout.topMargin = dp(y);
        controls.addView(view, layout);
    }

    private ImageView icon(int drawable, int color) {
        ImageView image = new ImageView(activity);
        image.setImageResource(drawable);
        image.setColorFilter(color);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return image;
    }

    private TextView text(String label, int size) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private int dp(float value) { return Math.round(value * density); }
}





















